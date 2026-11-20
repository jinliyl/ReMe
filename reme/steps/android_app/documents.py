"""Typed reads and user-controlled updates without a new storage component."""

import base64
import json

from ...components import R
from . import _state as state
from .base import AndroidRequestStep, public_run
from .capture import enqueue
from .schema import Read, Update


async def list_runs(runtime):
    """Read durable processing states newest first."""
    result = []
    for path in runtime.files.path("app_state/runs").glob("*.json"):
        result.append(await state.run(runtime, path.stem))
    return sorted(result, key=lambda item: (item["captured_at"], item["id"]), reverse=True)


@R.register("android_read_step")
class ReadStep(AndroidRequestStep):
    """Resolve typed object IDs instead of arbitrary file paths."""

    async def handle(self):
        request = self.request(Read)
        runtime = self.agent_runtime
        if request.kind in {"task", "idea"}:
            if request.id:
                return self.answer((await state.document(runtime, request.kind, request.id)).model_dump(mode="json"))
            items = [d.model_dump(mode="json") for d in await state.documents(runtime, request.kind)]
        elif request.kind == "run":
            return self.answer(public_run(await state.run(runtime, request.id or "")))
        elif request.kind == "conversation":
            items = [public_run(value) for value in await list_runs(runtime)]
        elif request.kind == "resource":
            value = await state.run(runtime, request.id or "")
            path = runtime.files.path(value["resource"] + "/audio.wav")
            self.require_paths(path)
            if not path.is_file() or path.stat().st_size > 8 * 1024 * 1024:
                raise ValueError("recording is unavailable")
            return self.answer({"audio": base64.b64encode(path.read_bytes()).decode(), "format": "wav"})
        else:
            directory = "daily" if request.kind == "journal" else "research"
            if request.date and request.kind == "journal":
                content = await state.read_text(runtime, f"{directory}/{request.date.isoformat()}/journal.md")
                return self.answer({"date": request.date.isoformat(), "content": content})
            items = []
            pattern = "*/journal.md" if directory == "daily" else "*/*.md"
            for path in sorted(runtime.files.path(directory).glob(pattern), reverse=True):
                if request.id and path.parent.name != request.id:
                    continue
                if request.date and not path.stem.startswith(request.date.isoformat()):
                    continue
                content = await state.read_text(runtime, path.relative_to(self.workspace_path).as_posix())
                items.append(
                    {
                        "id": path.parent.name if directory == "research" else None,
                        "date": path.parent.name if directory == "daily" else path.stem,
                        "content": content,
                    },
                )
        return self.answer({"items": items[request.offset : request.offset + request.limit], "total": len(items)})


@R.register("android_update_step")
class UpdateStep(AndroidRequestStep):
    """Apply explicit user decisions with revision checks."""

    async def handle(self):
        request = self.request(Update)
        runtime = self.agent_runtime
        async with state.mutation(runtime):
            previous = await state.read_json(runtime, f"app_state/operations/{request.operation_id}.json")
            payload = request.model_dump(mode="json", exclude_unset=True)
            if previous:
                result = await state.commit(runtime, request.operation_id, payload, [])
                if request.kind == "run":
                    current = await state.run(runtime, request.id)
                    if current["status"] == "queued":
                        await enqueue(runtime, current)
                return self.answer(result)
            if request.kind == "run":
                value = await state.run(runtime, request.id)
                if not value.get("attempt_revision", 1) <= request.expected_revision <= value["revision"]:
                    raise state.Conflict("run input attempt changed")
                if request.action == "cancel":
                    value.update(status="cancelled", revision=value["revision"] + 1)
                elif request.action in {"retry", "correct"}:
                    if request.action == "correct":
                        if not request.text or not request.text.strip():
                            raise ValueError("correct requires nonempty text")
                        value.setdefault("corrections", []).append(
                            {"text": request.text, "at": runtime.now().isoformat()},
                        )
                        value["text"] = request.text
                    elif value["status"] not in {"failed", "cancelled"}:
                        raise ValueError("only failed or cancelled runs can be retried")
                    value.update(answer="", preview="", message_id=None, error=None)
                    value.update(
                        status="queued",
                        attempt_revision=value["revision"] + 1,
                        revision=value["revision"] + 1,
                        attempt=value.get("attempt", 0) + 1,
                    )
                else:
                    raise ValueError("unsupported run action")
                result = public_run(value)
                extra = [
                    {"path": f"app_state/runs/{request.id}.json", "content": json.dumps(value, ensure_ascii=False)},
                ]
                await state.commit(runtime, request.operation_id, payload, [], result=result, extra=extra)
            else:
                item = await state.document(runtime, request.kind, request.id)
                if item.revision != request.expected_revision:
                    raise state.Conflict("document revision changed")
                if request.kind == "task":
                    statuses = {"complete": "done", "cancel": "cancelled", "reopen": "open"}
                    if request.action == "reschedule":
                        fields = request.model_fields_set & {"scheduled_at", "due_at", "remind_at"}
                        if not fields:
                            raise ValueError("reschedule requires a time field")
                        for field in fields:
                            setattr(item, field, getattr(request, field))
                    elif request.action in statuses:
                        item.status = statuses[request.action]
                    else:
                        raise ValueError("unsupported task action")
                    item.schedule_revision += 1
                elif request.action in {"pause", "resume"}:
                    item.status = "paused" if request.action == "pause" else "collecting"
                else:
                    raise ValueError("unsupported idea action")
                item.revision += 1
                if request.kind == "task":
                    item.input_revision += 1
                result = item.model_dump(mode="json")
                await state.commit(
                    runtime,
                    request.operation_id,
                    payload,
                    [
                        {
                            "expected_revision": request.expected_revision,
                            "expected_fingerprint": item.source_fingerprint,
                            "document": result,
                        },
                    ],
                    result=result,
                )
        if request.kind == "run" and value["status"] == "queued":
            await enqueue(runtime, value)
        return self.answer(result)
