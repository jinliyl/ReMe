"""Accept a retry-safe input before the existing event runtime starts inference."""

import base64
import io
import json
import wave
from datetime import datetime

from ..proactive.data_connectors.base_connector import BaseConnectorStep
from ...components import R
from ...schema import Event
from .base import AndroidRequestStep, public_run
from .schema import Capture, Connect
from . import _state as state


class InputConnector(BaseConnectorStep):
    """Publish mobile input using the same staged resource package contract."""

    source = "android"
    description = "用户主动保存的日记输入"

    def __init__(self, request, target, audio, **kwargs):
        super().__init__(**kwargs)
        self.request = request
        self.target = target
        self.audio = audio

    def resource_target(self):
        self.require_target()
        return self.target, datetime.now(self.request.captured_at.tzinfo)

    def require_target(self):
        """Reject publication outside the current workspace permissions."""
        from ..file_io import check_path_permission

        if not check_path_permission(self.workspace_path, self.target, self.context.get("_allowed_paths")):
            raise PermissionError("no permission to publish mobile input")

    def collect(self, directory):
        request = self.request.model_dump(mode="json", exclude={"audio"})
        (directory / "input.md").write_text(
            "---\n" + json.dumps(request, ensure_ascii=False) + "\n---\n\n" + (self.request.text or "语音记录") + "\n",
            encoding="utf-8",
        )
        files = [{"path": "input.md", "description": "原始输入与采集时间"}]
        if self.audio is not None:
            (directory / "audio.wav").write_bytes(self.audio)
            files.append({"path": "audio.wav", "description": "原始录音"})
        return files


def decode_audio(request):
    """Validate bounded mono PCM16 recordings before publication."""
    if request.kind != "audio":
        return None
    audio = base64.b64decode(request.audio, validate=True)
    if len(audio) > 8 * 1024 * 1024:
        raise ValueError("audio exceeds 8 MiB")
    with wave.open(io.BytesIO(audio)) as recording:
        if (recording.getnchannels(), recording.getsampwidth(), recording.getframerate()) != (1, 2, 16000):
            raise ValueError("audio must be mono PCM16 WAV at 16000 Hz")
        if recording.getnframes() / 16000 > 120 or recording.getnframes() == 0:
            raise ValueError("recording must contain between 0 and 120 seconds of audio")
    return audio


async def enqueue(runtime, value):
    """Persist one retry-safe event in the existing event stream."""
    await runtime.append(
        "diary",
        Event(
            id=f"{value['id']}-{value.get('attempt', 0)}",
            event_ts=value["captured_at"],
            source="android",
            description="处理用户新保存的日记输入",
            paths=[value["resource"] + "/metadata.json"],
            metadata={"run_id": value["id"]},
        ),
    )


@R.register("android_connect_step")
class ConnectStep(AndroidRequestStep):
    """Return durable server identity and mobile capabilities."""

    async def handle(self):
        self.request(Connect)
        runtime = self.agent_runtime
        identity = await state.instance(runtime)
        return self.answer(
            {
                **identity,
                "protocol_version": 1,
                "capabilities": {
                    "asr": True,
                    "tts": True,
                    "research": True,
                    "share_types": ["text/plain"],
                    "audio_format": "wav",
                    "sample_rate": 16000,
                    "max_recording_seconds": 120,
                    "max_audio_bytes": 8 * 1024 * 1024,
                },
            },
        )


@R.register("android_capture_step")
class CaptureStep(AndroidRequestStep):
    """Acknowledge input only after durable resource publication."""

    async def handle(self):
        request = self.request(Capture)
        audio = decode_audio(request)
        runtime = self.agent_runtime
        await state.recover(runtime)
        signature = state.fingerprint(request.model_dump(mode="json"))
        async with state.mutation(runtime):
            relative = f"app_state/runs/{request.capture_id}.json"
            value = await state.read_json(runtime, relative)
            if value and value["fingerprint"] != signature:
                raise state.Conflict("capture_id was already used with different content")
            if value is None:
                target = runtime.files.path(
                    f"{self.config_value('resource_dir')}/android/{request.captured_at:%Y-%m-%d}/"
                    f"{request.captured_at:%H%M%S}_{state.fingerprint(request.capture_id)[:16]}",
                )
                value = {
                    "id": request.capture_id,
                    "capture_id": request.capture_id,
                    "fingerprint": signature,
                    "captured_at": request.captured_at.isoformat(),
                    "timezone": request.timezone,
                    "kind": request.kind,
                    "text": request.text or "",
                    "status": "preparing",
                    "revision": 1,
                    "attempt": 0,
                    "attempt_revision": 1,
                    "resource": target.relative_to(self.workspace_path).as_posix(),
                    "target_kind": request.target_kind,
                    "target_id": request.target_id,
                }
                await state.write_json(runtime, relative, value)
            if value["status"] == "preparing":
                target = runtime.files.path(value["resource"])
                if not (target / "metadata.json").exists():
                    connector = InputConnector(request, target, audio, app_context=self.app_context)
                    await connector(self.context)
                value.update(status="queued", revision=value["revision"] + 1)
                await state.write_json(runtime, relative, value)
        if value["status"] == "queued":
            await enqueue(runtime, value)
        return self.answer({**public_run(value), "run_id": value["id"], "saved": True})
