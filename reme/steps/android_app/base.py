"""Existing AgentRuntime and speech dependencies shared by invocation-scoped Steps."""

from ..base_step import Ref
from ..proactive.agent.base import BaseAgentStep
from ...components.asr import BaseASR
from ...components.tts import BaseTTS
from ...enumeration import ComponentEnum
from . import _state as state
from ...enumeration import ChunkEnum
from ...schema import StreamChunk


class AndroidStep(BaseAgentStep):
    """Reuse application-owned components; helpers own no long-lived resources."""

    asr = Ref(BaseASR, ComponentEnum.ASR)
    tts = Ref(BaseTTS, ComponentEnum.TTS)

    def request(self, model):
        """Validate public inputs within the server-owned workspace."""
        self.require_paths(self.workspace_path)
        return model.model_validate(self.parameters())

    def answer(self, value):
        """Return application data in the existing ReMe response."""
        self.context.response.answer = value
        return self.context.response


class AndroidRequestStep(AndroidStep):
    """Expose stable mobile errors without leaking provider bodies or file content."""

    async def execute(self):
        try:
            return await self.handle()
        except Exception as exc:
            code = "service_unavailable"
            if isinstance(exc, state.Conflict):
                code = "revision_conflict"
            elif isinstance(exc, ValueError):
                code = "invalid_input"
            elif isinstance(exc, PermissionError):
                code = "access_denied"
            response = self.context.response
            response.success = False
            response.answer = {"error": {"code": code}}
            response.metadata["error"] = {"type": type(exc).__name__, "module": type(exc).__module__}
            if self.context.stream_queue is not None:
                await self.context.add_stream_chunk(StreamChunk(chunk_type=ChunkEnum.ERROR, chunk=code))
            return response

    async def handle(self):
        """Implement the authenticated application operation."""
        raise NotImplementedError


def public_run(value):
    """Only user-facing fields cross the mobile API, never native events or paths."""
    keys = {
        "id",
        "capture_id",
        "kind",
        "captured_at",
        "status",
        "revision",
        "attempt",
        "text",
        "answer",
        "message_id",
        "error",
        "documents",
        "preview",
    }
    return {key: value[key] for key in keys if key in value}
