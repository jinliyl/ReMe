"""Opt-in real HTTP, Codex, TTS and ASR validation in an isolated workspace.

Run from the intended checkout with --env-file. Never prints credentials or
copies the supplied environment file into the checkout.
"""

import argparse
import asyncio
import base64
from contextlib import asynccontextmanager
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
from uuid import uuid4
from zoneinfo import ZoneInfo

import httpx
import uvicorn
from dotenv import dotenv_values

from reme.application import Application
from reme.config import resolve_app_config
from reme.schema import Event


@asynccontextmanager
async def server(workspace):
    """Run a real HTTP listener and close all application resources."""
    config = resolve_app_config(config="android_app", log_config=False, workspace_dir=str(workspace))
    config.update(enable_logo=False, log_to_file=False, log_to_console=False)
    app = Application(**config)
    service = app.context.service
    service.build_service(app)
    service.add_jobs(app)
    service.finalize_service(app)
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    worker = uvicorn.Server(uvicorn.Config(service.service, log_level="error", access_log=False, lifespan="on"))
    task = asyncio.create_task(worker.serve(sockets=[sock]))
    try:
        async with asyncio.timeout(45):
            while not worker.started:
                if task.done():
                    await task
                    raise RuntimeError("HTTP server did not start")
                await asyncio.sleep(0.05)
        async with httpx.AsyncClient(
            base_url=f"http://127.0.0.1:{sock.getsockname()[1]}",
            timeout=400,
            headers={"Authorization": "Bearer " + os.environ["ANDROID_APP_TOKEN"]},
        ) as client:
            yield app, client
    finally:
        worker.should_exit = True
        try:
            await asyncio.wait_for(task, 20)
        finally:
            sock.close()


async def post(client, job, **payload):
    """Require the existing success envelope over authenticated HTTP."""
    response = await client.post("/" + job, json=payload)
    response.raise_for_status()
    value = response.json()
    assert value["success"], f"{job} returned a failed business response"
    return value["answer"]


async def wait_run(client, run_id):
    """Wait for a persisted terminal outcome without holding an SSE connection."""
    async with asyncio.timeout(360):
        while True:
            value = await post(client, "android_read", kind="run", id=run_id)
            if value["status"] in {"completed", "failed", "cancelled"}:
                assert value["status"] == "completed", f"run failed at provider boundary: {value.get('error')}"
                return value
            await asyncio.sleep(0.5)


async def validate(workspace, report):
    """Exercise real provider boundaries and restart the same workspace."""
    token = os.environ["ANDROID_APP_TOKEN"]
    async with server(workspace) as (app, client):
        unauthorized = await client.post(
            "/android_connect",
            json={"device_id": "real_test"},
            headers={"Authorization": "Bearer invalid"},
        )
        assert unauthorized.status_code == 401
        injected = await client.post("/android_connect", json={"device_id": "real_test", "_allowed_paths": ["/"]})
        assert injected.status_code == 422
        connection = await post(client, "android_connect", device_id="real_test")
        report["authentication"] = "passed"
        print("PASS authenticated HTTP and context-injection rejection", flush=True)
        tts = app.context.components["tts"]["default"]
        speech = await tts.synthesize("我想做一个安卓语音日记本。明天上午九点提醒我研究语音输入方案。")
        source = workspace / "test-source.mp3"
        recording = workspace / "test-input.wav"
        source.write_bytes(speech.audio)
        conversion = subprocess.run(
            [
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-i",
                str(source),
                "-ar",
                "16000",
                "-ac",
                "1",
                "-c:a",
                "pcm_s16le",
                str(recording),
            ],
            capture_output=True,
            check=False,
        )
        assert conversion.returncode == 0, "real TTS audio could not be decoded"
        report["tts_source_bytes"] = len(speech.audio)
        print("PASS real TTS produced decodable speech", flush=True)
        capture_id = uuid4().hex
        captured_at = datetime.now(ZoneInfo("Asia/Shanghai"))
        payload = {
            "capture_id": capture_id,
            "captured_at": captured_at.isoformat(),
            "timezone": "Asia/Shanghai",
            "kind": "audio",
            "audio": base64.b64encode(recording.read_bytes()).decode(),
        }
        accepted = await post(client, "android_capture", **payload)
        assert accepted["saved"]
        repeated = await post(client, "android_capture", **payload)
        assert repeated["run_id"] == accepted["run_id"]
        # Drop observation immediately; the runtime must still finish independently.
        async with client.stream("POST", "/android_events", json={"run_id": accepted["run_id"]}) as response:
            async for line in response.aiter_lines():
                if line.startswith("data:"):
                    break
        first = await wait_run(client, accepted["run_id"])
        assert "日记" in first["text"] and "九点" in first["text"].replace("9点", "九点")
        report["asr_transcript"] = first["text"]
        report["first_reply"] = first["answer"]
        print("PASS real ASR, Codex, durable processing after SSE disconnect", flush=True)
        sync = await post(client, "android_sync", device_id="real_test")
        tasks = [d for d in sync["documents"] if d["kind"] == "task"]
        ideas = [d for d in sync["documents"] if d["kind"] == "idea"]
        assert tasks and ideas, "model did not organize both task and idea"
        task, idea = tasks[0], ideas[0]
        assert task["remind_at"] is not None, "explicit spoken reminder was not scheduled"
        scheduled = datetime.fromisoformat(task["remind_at"])
        assert scheduled.date() == (captured_at + timedelta(days=1)).date() and scheduled.hour == 9
        assert (workspace / "TODO.md").is_file() and (workspace / "IDEAS.md").is_file()
        assert list((workspace / "daily").glob("*/journal.md"))
        reply_audio = await post(client, "android_tts", run_id=first["id"], message_id=first["message_id"])
        assert reply_audio["is_last"] and len(base64.b64decode(reply_audio["audio"])) > 100
        print("PASS voice reply, Markdown documents and dated reminder", flush=True)
        second_id = uuid4().hex
        await post(
            client,
            "android_capture",
            capture_id=second_id,
            captured_at=captured_at.isoformat(),
            timezone="Asia/Shanghai",
            kind="text",
            target_kind="idea",
            target_id=idea["id"],
            text="继续刚才的安卓语音日记本，第一版先做点击录音，暂时不做实时通话。请把这个决定更新到已有想法。",
        )
        second = await wait_run(client, second_id)
        updated_idea = await post(client, "android_read", kind="idea", id=idea["id"])
        assert capture_id in updated_idea["source_ids"] and second_id in updated_idea["source_ids"]
        assert updated_idea["revision"] > idea["revision"]
        report["history_association"] = "passed"
        print("PASS real model merged follow-up into historical idea", flush=True)
        # Exercise research immediately rather than waiting for the next cron interval.
        runtime = app.context.components["agent_runtime"]["default"]
        await runtime.append(
            "idea_research",
            Event(
                id="real-research",
                event_ts=runtime.now(),
                source="integration",
                description="预研刚才的想法，准备讨论",
                paths=[f"ideas/{idea['id']}.md"],
            ),
        )
        response = await app.context.jobs["android_run"](agent_id="idea_research")
        assert response.success, "real research job failed: " + str(response.answer)
        researched = await post(client, "android_read", kind="idea", id=idea["id"])
        assert researched["researched_input_revision"] == researched["input_revision"]
        assert researched["status"] == "ready_for_discussion"
        report["research"] = "passed"
        print("PASS real proactive idea research", flush=True)
        current_task = await post(client, "android_read", kind="task", id=task["id"])
        operation_id = uuid4().hex
        completed = await post(
            client,
            "android_update",
            operation_id=operation_id,
            kind="task",
            id=task["id"],
            action="complete",
            expected_revision=current_task["revision"],
        )
        assert completed["status"] == "done"
        replay = await post(
            client,
            "android_update",
            operation_id=operation_id,
            kind="task",
            id=task["id"],
            action="complete",
            expected_revision=current_task["revision"],
        )
        assert replay == completed
        synced = await post(client, "android_sync", device_id="real_test", cursor=sync["cursor"])
        assert all(r["task_id"] != task["id"] for r in synced["reminders"])
        report["idempotence_and_reminder_cancellation"] = "passed"
        instance_id = connection["instance_id"]
    async with server(workspace) as (_app, client):
        connection = await post(client, "android_connect", device_id="real_test")
        assert connection["instance_id"] == instance_id
        restored = await post(client, "android_read", kind="task", id=task["id"])
        assert restored["status"] == "done"
        persisted = await post(client, "android_read", kind="run", id=second_id)
        assert persisted["answer"] == second["answer"]
        report["restart_recovery"] = "passed"
        print("PASS real service restart and source-file recovery", flush=True)
    assert token not in json.dumps(report, ensure_ascii=False)


async def main():
    """Load explicitly supplied credentials and write a sanitized test report."""
    parser = argparse.ArgumentParser()
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--report", default="/tmp/reme-android-real-report.json")
    args = parser.parse_args()
    for key, value in dotenv_values(args.env_file).items():
        if value is not None:
            os.environ[key] = value
    os.environ["ANDROID_APP_TOKEN"] = secrets.token_urlsafe(32)
    from reme.steps.android_app.conversation import RunStep

    original_process = RunStep.process_input

    async def diagnose(step, value):
        try:
            return await original_process(step, value)
        except Exception as error:
            detail = str(error)
            for key, secret in os.environ.items():
                if any(word in key for word in ("KEY", "TOKEN", "SECRET", "PASSWORD")) and len(secret) > 7:
                    detail = detail.replace(secret, "[REDACTED]")
            print("Pipeline diagnostic:", type(error).__name__, detail[:1500], flush=True)
            raise

    RunStep.process_input = diagnose
    report = {"checkout": str(Path(__file__).resolve().parents[2]), "started_at": datetime.now().isoformat()}
    try:
        with tempfile.TemporaryDirectory(prefix="reme-android-real-") as directory:
            await validate(Path(directory), report)
        report["success"] = True
    finally:
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("Sanitized report:", args.report, flush=True)


if __name__ == "__main__":
    asyncio.run(main())
