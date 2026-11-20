"""Exercise public boundaries and durable recovery without provider access."""

# pylint: disable=redefined-outer-name

import asyncio
import json
from pathlib import Path

import httpx
import pytest
import pytest_asyncio
from pydantic import ValidationError

from reme.application import Application
from reme.components.agent_wrapper import BaseAgentWrapper
from reme.components.asr import BaseASR
from reme.components.tts import BaseTTS
from reme.components.service.http_auth import HttpAuthConfig
from reme.config import resolve_app_config
from reme.enumeration import ChunkEnum
from reme.schema import ASRResult, SessionSnapshot, SessionStatus, StreamChunk, TTSResult
from reme.steps.android_app import _state as state
from reme.steps.android_app.schema import Document

TOKEN = "test-android-token-" + "x" * 32


class Wrapper(BaseAgentWrapper):
    """A controllable native-session boundary for publication race tests."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.sessions = set()
        self.calls = 0
        self.gate = None
        self.draft = {
            "answer": "已记下你的想法，并安排了明天九点的提醒。",
            "journal": "用户希望制作语音日记本。",
            "tasks": [
                {
                    "title": "研究语音方案",
                    "content": "比较端侧和服务端识别。",
                    "time_evidence": "明天九点",
                    "scheduled_at": "2026-11-17T09:00:00+08:00",
                    "remind_at": "2026-11-17T09:00:00+08:00",
                },
            ],
            "ideas": [{"title": "安卓语音日记本", "content": "以语音为主要入口。"}],
        }

    async def create_session(self, **_kwargs):
        value = f"session_{len(self.sessions)}"
        self.sessions.add(value)
        return value

    async def session_exists(self, session_id):
        return session_id in self.sessions

    async def get_session_status(self, session_id):
        return SessionStatus(session_id=session_id, status="idle")

    async def read_session(self, session_id):
        return SessionSnapshot(session_id=session_id, state=await self.get_session_status(session_id))

    async def reply_stream(self, _inputs, **kwargs):
        self.calls += 1
        if self.gate:
            await self.gate.wait()
        text = json.dumps(self.draft, ensure_ascii=False)
        yield StreamChunk(chunk_type=ChunkEnum.CONTENT, chunk=text)
        yield StreamChunk(
            chunk_type=ChunkEnum.REPLY_END,
            chunk={
                "session_id": kwargs["resume"],
                "result": text,
                "structured_output": self.draft,
                "usage": None,
                "status": "completed",
                "error": None,
                "raw": {},
            },
        )


class ASR(BaseASR):
    """Return deterministic recognition without network access."""

    async def transcribe(self, *_args, **_kwargs):
        return ASRResult(text="做一个安卓语音日记本，明天九点提醒我研究语音方案。", model="fake")


class TTS(BaseTTS):
    """Supply a deterministic asynchronous audio stream."""

    def stream(self, _text, **_kwargs):
        async def chunks():
            yield TTSResult(audio=b"audio", format="wav", sample_rate=16000, model="fake")

        return chunks()


@pytest_asyncio.fixture
async def app_client(tmp_path, monkeypatch):
    """Assemble real service and runtime components in an isolated workspace."""
    monkeypatch.setenv("ANDROID_APP_TOKEN", TOKEN)
    config = resolve_app_config(config="android_app", log_config=False, workspace_dir=str(tmp_path))
    config.update(enable_logo=False, log_to_file=False, log_to_console=False)
    config["service"]["max_request_bytes"] = 1024
    app = Application(**config)
    wrapper = Wrapper(app_context=app.context)
    for key, component in [
        ("agent_wrapper", wrapper),
        ("asr", ASR(app_context=app.context)),
        ("tts", TTS(app_context=app.context)),
    ]:
        app.context.components[key]["default"] = component
    service = app.context.service
    service.build_service(app)
    service.add_jobs(app)
    async with app:
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=service.service),
            base_url="http://test",
            headers={"Authorization": "Bearer " + TOKEN},
        ) as client:
            yield app, client, wrapper


async def call(client, name, **body):
    """Require a successful public JSON operation."""
    response = await client.post("/android_" + name, json=body)
    assert response.status_code == 200
    value = response.json()
    assert value["success"], value["answer"]
    return value["answer"]


async def capture(client, identifier="capture_one"):
    """Save one deterministic input through HTTP."""
    return await call(
        client,
        "capture",
        capture_id=identifier,
        captured_at="2026-11-16T10:00:00+08:00",
        kind="text",
        text="做一个安卓语音日记本，明天九点提醒我研究语音方案。",
    )


async def complete(client, identifier):
    """Wait for runtime-owned processing instead of invoking it directly."""
    async with asyncio.timeout(10):
        while True:
            value = await call(client, "read", kind="run", id=identifier)
            if value["status"] in {"completed", "failed"}:
                assert value["status"] == "completed", value
                return value
            await asyncio.sleep(0.02)


def test_auth_configuration_fails_closed_and_hides_secret():
    """Auth configuration fails closed and hides secret."""
    with pytest.raises(ValidationError) as exc:
        HttpAuthConfig(enabled=True, token="private-short-value")
    assert "private-short-value" not in str(exc.value)
    config = HttpAuthConfig(enabled=True, token=TOKEN)
    assert TOKEN not in repr(config)
    assert "token" not in config.model_dump()


@pytest.mark.asyncio
async def test_auth_limits_and_injection(app_client):
    """Auth limits and injection."""
    app, client, wrapper = app_client
    for path in ["/android_connect", "/android_events", "/android_tts", "/docs", "/openapi.json"]:
        response = await client.post(path, content=b"x" * 2000, headers={"Authorization": "Bearer wrong"})
        assert response.status_code == 401
        assert response.headers["www-authenticate"] == "Bearer"
    response = await client.post("/android_connect", content=b"x" * 2000)
    assert response.status_code == 413
    for field in ["_allowed_paths", "workspace_dir", "_agent_id", "metadata"]:
        response = await client.post("/android_connect", json={"device_id": "phone", field: "/"})
        assert response.status_code == 422
    assert wrapper.calls == 0
    assert not any(route.path.startswith("/_reme/") for route in app.context.service.service.routes)
    assert (await call(client, "connect", device_id="phone"))["protocol_version"] == 1


@pytest.mark.asyncio
async def test_capture_idempotence_sync_and_completion(app_client):
    """Capture idempotence sync and completion."""
    app, client, wrapper = app_client
    first = await capture(client)
    repeated = await capture(client)
    assert first["run_id"] == repeated["run_id"]
    value = await complete(client, first["run_id"])
    assert wrapper.calls == 1
    sync = await call(client, "sync", device_id="phone")
    task = next(item for item in sync["documents"] if item["kind"] == "task")
    assert sync["reminders"][0]["task_id"] == task["id"]
    todo = (Path(app.context.app_config.workspace_dir) / "TODO.md").read_text(encoding="utf-8")
    assert "提醒 2026-11-17T09:00:00+08:00" in todo
    assert (Path(app.context.app_config.workspace_dir) / "IDEAS.md").is_file()
    payload = {
        "operation_id": "finish_one",
        "kind": "task",
        "id": task["id"],
        "action": "complete",
        "expected_revision": task["revision"],
    }
    result = await call(client, "update", **payload)
    assert result == await call(client, "update", **payload)
    assert result["status"] == "done"
    after = await call(client, "sync", device_id="phone", cursor=sync["cursor"])
    assert not after["reminders"]
    speech = await call(client, "tts", run_id=value["id"], message_id=value["message_id"])
    assert speech["is_last"]
    response = await client.post(
        "/android_capture",
        json={
            "capture_id": first["run_id"],
            "captured_at": "2026-11-16T10:00:00+08:00",
            "text": "different",
        },
    )
    assert not response.json()["success"]
    response = await client.post("/android_update", json={**payload, "operation_id": "stale"})
    assert not response.json()["success"]


@pytest.mark.asyncio
async def test_sse_outcome_and_unknown_origin_data(app_client):
    """Sse outcome and unknown origin data."""
    _app, client, _wrapper = app_client
    accepted = await capture(client)
    await complete(client, accepted["run_id"])
    response = await client.post("/android_events", json={"run_id": accepted["run_id"]})
    assert "data:[DONE]" in response.text
    assert '"chunk_type":"reply_end"' in response.text
    assert '"status":"completed"' in response.text
    assert "api_key" not in response.text
    assert "structured_output" in response.text


@pytest.mark.asyncio
async def test_user_file_edit_and_delete_are_synchronized(app_client):
    """User file edit and delete are synchronized."""
    app, client, _wrapper = app_client
    await capture(client)
    await complete(client, "capture_one")
    before = await call(client, "sync", device_id="phone")
    task = next(d for d in before["documents"] if d["kind"] == "task")
    runtime = app.context.components["agent_runtime"]["default"]
    path = runtime.files.path(f"tasks/{task['id']}.md")
    path.write_text(path.read_text().replace("研究语音方案", "用户手工编辑的任务"))
    after = await call(client, "sync", device_id="phone", cursor=before["cursor"])
    updated = next(d for d in after["documents"] if d["id"] == task["id"])
    assert updated["title"] == "用户手工编辑的任务"
    assert updated["revision"] > task["revision"]
    path.unlink()
    deleted = await call(client, "sync", device_id="phone", cursor=after["cursor"])
    assert {"kind": "task", "id": task["id"]} in deleted["deleted"]
    assert not deleted["reminders"]


@pytest.mark.asyncio
async def test_interrupted_commit_recovers_without_overwriting(app_client, monkeypatch):
    """Interrupted commit recovers without overwriting."""
    app, _client, _wrapper = app_client
    runtime = app.context.components["agent_runtime"]["default"]
    original = state.write_json
    crashed = False

    async def fail_commit(owner, relative, value):
        nonlocal crashed
        if relative == "app_state/operations/crash.json" and value.get("state") == "committed" and not crashed:
            crashed = True
            raise OSError("simulated crash")
        await original(owner, relative, value)

    monkeypatch.setattr(state, "write_json", fail_commit)
    item = Document(id="crash_task", kind="task", title="Durable source")
    with pytest.raises(OSError):
        async with state.mutation(runtime):
            await state.commit(
                runtime,
                "crash",
                {"id": "crash"},
                [{"expected_revision": 0, "document": item.model_dump(mode="json")}],
            )
    await state.recover(runtime)
    restored = await state.document(runtime, "task", item.id)
    assert restored.revision == 1 and restored.last_operation_id == "crash"
    assert (await state.receipts(runtime))[-1]["id"] == "crash"


@pytest.mark.asyncio
async def test_cancel_then_retry_cannot_publish_old_attempt(app_client):
    """Cancel then retry cannot publish old attempt."""
    app, client, wrapper = app_client
    wrapper.gate = asyncio.Event()
    first = await capture(client)
    async with asyncio.timeout(5):
        while not wrapper.calls:
            await asyncio.sleep(0.01)
    current = await call(client, "read", kind="run", id=first["run_id"])
    stopped = await call(
        client,
        "update",
        operation_id="stop_attempt",
        kind="run",
        id=current["id"],
        action="cancel",
        expected_revision=current["revision"],
    )
    await call(
        client,
        "update",
        operation_id="retry_attempt",
        kind="run",
        id=current["id"],
        action="retry",
        expected_revision=stopped["revision"],
    )
    wrapper.gate.set()
    await complete(client, current["id"])
    receipts = await state.receipts(app.context.components["agent_runtime"]["default"])
    organized = [r for r in receipts if r["id"].startswith("organize_")]
    assert len(organized) == 1
    run = await state.run(app.context.components["agent_runtime"]["default"], current["id"])
    assert run["draft_attempt"] == 1


@pytest.mark.asyncio
async def test_pending_journal_never_overwrites_manual_edit(app_client, monkeypatch):
    """Pending journal never overwrites manual edit."""
    app, _client, _wrapper = app_client
    runtime = app.context.components["agent_runtime"]["default"]
    await state.write_text(runtime, "daily/2026-11-16/journal.md", "original")
    original = state.replay

    async def crash(*_args):
        raise OSError("interrupted before publication")

    monkeypatch.setattr(state, "replay", crash)
    with pytest.raises(OSError):
        async with state.mutation(runtime):
            await state.commit(
                runtime,
                "pending_journal",
                {},
                [],
                extra=[{"path": "daily/2026-11-16/journal.md", "content": "generated"}],
            )
    monkeypatch.setattr(state, "replay", original)
    await state.write_text(runtime, "daily/2026-11-16/journal.md", "manual edit")
    with pytest.raises(state.Conflict):
        await state.recover(runtime)
    assert await state.read_text(runtime, "daily/2026-11-16/journal.md") == "manual edit"


@pytest.mark.asyncio
async def test_time_without_user_evidence_does_not_schedule(app_client):
    """Time without user evidence does not schedule."""
    _app, client, wrapper = app_client
    wrapper.draft["tasks"][0]["time_evidence"] = "你可以明天九点做"
    await capture(client)
    await complete(client, "capture_one")
    sync = await call(client, "sync", device_id="phone")
    assert not sync["reminders"]


@pytest.mark.asyncio
async def test_validation_errors_do_not_echo_user_input(app_client):
    """Validation errors do not echo user input."""
    _app, client, _wrapper = app_client
    response = await client.post(
        "/android_capture",
        json={
            "capture_id": "bad_input",
            "captured_at": "2026-11-16T09:00:00+08:00",
            "text": "private-sentinel",
            "kind": "audio",
        },
    )
    body = response.json()
    assert not body["success"]
    assert body["answer"]["error"]["code"] == "invalid_input"
    assert "private-sentinel" not in response.text


@pytest.mark.asyncio
async def test_request_validation_rejects_ambiguous_action_fields(app_client):
    """Do not silently ignore a time field sent with an unrelated action."""
    _app, client, _wrapper = app_client
    response = await client.post(
        "/android_update",
        json={
            "operation_id": "invalid_time",
            "kind": "task",
            "id": "unknown",
            "action": "complete",
            "expected_revision": 1,
            "remind_at": "2026-11-17T09:00:00+08:00",
        },
    )
    assert response.json()["answer"]["error"]["code"] == "invalid_input"
    response = await client.post(
        "/android_capture",
        json={
            "capture_id": "timezone",
            "captured_at": "2026-11-16T09:00:00+08:00",
            "text": "private",
            "timezone": "unknown-zone",
        },
    )
    assert response.json()["answer"]["error"]["code"] == "invalid_input"


@pytest.mark.asyncio
async def test_research_budget_counts_failed_provider_attempts(app_client, monkeypatch):
    """A failing provider must not bypass the configured daily spending bound."""
    app, client, _wrapper = app_client
    await capture(client)
    await complete(client, "capture_one")
    runtime = app.context.components["agent_runtime"]["default"]
    calls = 0

    async def fail_inference(*_args, **_kwargs):
        nonlocal calls
        calls += 1
        raise RuntimeError("temporary provider error")

    monkeypatch.setattr("reme.steps.android_app.research.infer", fail_inference)
    from reme.schema import Event

    for index in range(4):
        await runtime.append(
            "idea_research",
            Event(id=f"budget_{index}", event_ts=runtime.now(), source="test", description="test"),
        )
        await app.context.jobs["android_run"](agent_id="idea_research")
    assert calls == 3
    receipts = await state.receipts(runtime)
    assert len([r for r in receipts if r["id"].startswith("research_attempt_")]) == 3


@pytest.mark.asyncio
async def test_invalid_proposal_never_leaves_unreplayable_intent(app_client):
    """Reject malformed documents before persisting their operation intent."""
    app, _client, _wrapper = app_client
    runtime = app.context.components["agent_runtime"]["default"]
    invalid = {"id": "bad", "kind": "task", "title": "x", "content": "x" * 50001}
    with pytest.raises(ValidationError):
        async with state.mutation(runtime):
            await state.commit(runtime, "invalid_doc", {}, [{"expected_revision": 0, "document": invalid}])
    assert await state.read_json(runtime, "app_state/operations/invalid_doc.json") is None


@pytest.mark.asyncio
async def test_progress_snapshots_do_not_prevent_user_cancellation(app_client):
    """A new preview is not a conflicting user input revision."""
    app, client, wrapper = app_client
    wrapper.gate = asyncio.Event()
    accepted = await capture(client)
    runtime = app.context.components["agent_runtime"]["default"]
    snapshot = await call(client, "read", kind="run", id=accepted["run_id"])
    for number in range(3):
        await state.update_run(runtime, accepted["run_id"], preview=f"partial {number}")
    stopped = await call(
        client,
        "update",
        operation_id="stop_after_preview",
        kind="run",
        id=accepted["run_id"],
        action="cancel",
        expected_revision=snapshot["revision"],
    )
    assert stopped["status"] == "cancelled"
    restarted = await call(
        client,
        "update",
        operation_id="restart_after_preview",
        kind="run",
        id=accepted["run_id"],
        action="retry",
        expected_revision=stopped["revision"],
    )
    assert restarted["status"] == "queued"
    stale = await client.post(
        "/android_update",
        json={
            "operation_id": "old_attempt_stop",
            "kind": "run",
            "id": accepted["run_id"],
            "action": "cancel",
            "expected_revision": stopped["revision"],
        },
    )
    assert stale.json()["answer"]["error"]["code"] == "revision_conflict"
    wrapper.gate.set()


@pytest.mark.asyncio
async def test_inference_cannot_overwrite_manual_edit_with_unchanged_revision(app_client):
    """Direct Markdown edits remain authoritative even before sync assigns a new revision."""
    app, client, wrapper = app_client
    await capture(client)
    await complete(client, "capture_one")
    runtime = app.context.components["agent_runtime"]["default"]
    idea = next(item for item in await state.documents(runtime) if item.kind == "idea")
    wrapper.gate = asyncio.Event()
    wrapper.draft["ideas"] = [
        {"id": idea.id, "expected_revision": idea.revision, "title": idea.title, "content": "stale generated content"},
    ]
    wrapper.draft["tasks"] = []
    await capture(client, "manual_edit_race")
    async with asyncio.timeout(5):
        while wrapper.calls < 2:
            await asyncio.sleep(0.01)
    path = runtime.files.path(state.document_path(idea.kind, idea.id))
    path.write_text(path.read_text().replace(idea.content, "manual source is authoritative"))
    wrapper.gate.set()
    async with asyncio.timeout(5):
        while (await state.run(runtime, "manual_edit_race"))["status"] not in {"failed", "completed"}:
            await asyncio.sleep(0.01)
    assert (await state.run(runtime, "manual_edit_race"))["status"] == "failed"
    assert (await state.document(runtime, "idea", idea.id)).content == "manual source is authoritative"
