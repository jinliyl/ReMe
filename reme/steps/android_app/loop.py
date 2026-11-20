"""Bootstrap recovery and delegate scheduling to existing proactive infrastructure."""

from ..proactive.agent.loop import ProactiveLoopStep
from ...components import R
from . import _state as state
from .base import AndroidStep
from .capture import enqueue
from .documents import list_runs


@R.register("android_loop_step")
class LoopStep(AndroidStep):
    """Recover saved work before starting the existing scheduler."""

    async def execute(self):
        runtime = self.agent_runtime
        await state.recover(runtime)
        for value in reversed(await list_runs(runtime)):
            if value["status"] not in {"completed", "cancelled", "failed", "preparing"}:
                await state.update_run(runtime, value["id"], status="queued")
                await enqueue(runtime, value)
        loop = ProactiveLoopStep(app_context=self.app_context, job="android_run")
        return await loop(self.context)
