"""Authenticated snapshots, incremental sync, speech and reminder receipts."""

import asyncio
import base64
import json

from ...components import R
from ...enumeration import ChunkEnum
from ...schema import StreamChunk
from . import _state as state
from .base import AndroidRequestStep, public_run
from .documents import list_runs
from .schema import Ack, Events, Speech, Sync


async def reconcile(runtime):
    """Turn user-owned file edits into ordinary versioned changes, preserving sources."""
    async with state.mutation(runtime):
        known = {}
        for receipt in await state.receipts(runtime):
            for change in receipt["changes"]:
                doc = change["document"]
                known[(doc["kind"], doc["id"])] = doc
            for deleted in receipt["result"].get("deleted", []):
                known.pop((deleted["kind"], deleted["id"]), None)
        current = {(d.kind, d.id): d for d in await state.documents(runtime)}
        for key, item in current.items():
            prior = known.get(key)
            clean = item.model_dump(mode="json", exclude={"last_operation_id"})
            previous = {k: v for k, v in (prior or {}).items() if k != "last_operation_id"}
            if clean == previous:
                continue
            expected = item.revision
            item.revision = max(expected, (prior or {}).get("revision", 0)) + 1
            item.input_revision += 1
            if prior and item.kind == "task":
                if any(clean[k] != prior.get(k) for k in ("status", "scheduled_at", "due_at", "remind_at")):
                    item.schedule_revision += 1
            signature = state.fingerprint([clean, runtime.files.path(state.document_path(*key)).stat().st_mtime_ns])
            await state.commit(
                runtime,
                f"external_{signature[:24]}",
                {"fingerprint": signature},
                [{"expected_revision": expected, "document": item.model_dump(mode="json")}],
            )
        for key, prior in known.items():
            if key not in current and not runtime.files.path(state.document_path(*key)).exists():
                payload = {"deleted": [{"kind": key[0], "id": key[1]}]}
                await state.commit(
                    runtime,
                    f"deleted_{state.fingerprint([key, prior])[:24]}",
                    payload,
                    [],
                    result=payload,
                )


@R.register("android_sync_step")
class SyncStep(AndroidRequestStep):
    """Expose file edits, tombstones and the complete reminder plan."""

    async def handle(self):
        request = self.request(Sync)
        runtime = self.agent_runtime
        identity = await state.instance(runtime)
        await reconcile(runtime)
        history = await state.receipts(runtime)
        try:
            epoch, position = (request.cursor or "").split(":", 1)
            sequence = int(position)
            reset = epoch != identity["epoch"] or sequence < 0 or sequence > (history[-1]["sequence"] if history else 0)
        except ValueError:
            sequence, reset = 0, True
        if reset:
            sequence = 0
        page = [item for item in history if item["sequence"] > sequence][: request.limit]
        ids, deleted = {}, []
        for receipt in page:
            for change in receipt["changes"]:
                d = change["document"]
                ids[(d["kind"], d["id"])] = None
            deleted.extend(receipt["result"].get("deleted", []))
        items = []
        for kind, identifier in ids:
            try:
                items.append((await state.document(runtime, kind, identifier)).model_dump(mode="json"))
            except ValueError:
                if not runtime.files.path(state.document_path(kind, identifier)).exists():
                    deleted.append({"kind": kind, "id": identifier})
        next_sequence = page[-1]["sequence"] if page else sequence
        reminders = [value for item in await state.documents(runtime, "task") if (value := state.reminder(item))]
        runs = [public_run(value) for value in (await list_runs(runtime))[:30]]
        return self.answer(
            {
                **identity,
                "reset": reset,
                "documents": items,
                "deleted": deleted,
                "runs": runs,
                "reminders": reminders,
                "reminders_complete": True,
                "cursor": f"{identity['epoch']}:{next_sequence}",
                "has_more": bool(history and next_sequence < history[-1]["sequence"]),
            },
        )


@R.register("android_events_step")
class EventsStep(AndroidRequestStep):
    """Observe processing without owning or cancelling it."""

    async def handle(self):
        request = self.request(Events)
        runtime = self.agent_runtime
        revision = None
        while True:
            value = await state.run(runtime, request.run_id)
            if revision != value["revision"]:
                revision = value["revision"]
                await self.context.add_stream_chunk(
                    StreamChunk(
                        chunk_type=ChunkEnum.DATA,
                        event="end",
                        chunk=public_run(value),
                        metadata={"kind": "run_snapshot", "revision": revision},
                    ),
                )
                if value.get("preview"):
                    await self.context.add_stream_chunk(
                        StreamChunk(
                            chunk_type=ChunkEnum.CONTENT,
                            event="end",
                            chunk=value["preview"],
                            message_id=value.get("message_id", f"message_{request.run_id}"),
                        ),
                    )
                if value["status"] in {"completed", "failed", "cancelled"}:
                    status = {"cancelled": "interrupted", "completed": "completed", "failed": "failed"}[value["status"]]
                    await self.context.add_stream_chunk(
                        StreamChunk(
                            chunk_type=ChunkEnum.REPLY_END,
                            event="end",
                            status=status,
                            chunk={
                                "session_id": "",
                                "result": value.get("answer", ""),
                                "structured_output": None,
                                "status": status,
                                "error": value.get("error"),
                                "usage": None,
                                "raw": {},
                            },
                        ),
                    )
                    return self.context.response
            await asyncio.sleep(0.2)


@R.register("android_tts_step")
class SpeechStep(AndroidRequestStep):
    """Speak only committed assistant answers."""

    async def handle(self):
        request = self.request(Speech)
        value = await state.run(self.agent_runtime, request.run_id)
        if value["status"] != "completed" or value.get("message_id") != request.message_id:
            raise ValueError("only published assistant messages can be spoken")
        # A speech retry has no effect on inference or diary publication.
        audio = await self.tts.synthesize(value["answer"], **({"voice": request.voice} if request.voice else {}))
        return self.answer(
            {**audio.model_dump(mode="json", exclude={"audio"}), "audio": base64.b64encode(audio.audio).decode()},
        )


@R.register("android_ack_step")
class AckStep(AndroidRequestStep):
    """Persist device notification receipts idempotently."""

    async def handle(self):
        request = self.request(Ack)
        runtime = self.agent_runtime
        async with state.mutation(runtime):
            path = f"app_state/devices/{request.device_id}.json"
            value = await state.read_json(runtime, path, {})
            entry = value.setdefault(request.reminder_id, {})
            entry.setdefault(request.state, runtime.now().isoformat())
            result = {"reminder_id": request.reminder_id, "state": request.state}
            return self.answer(
                await state.commit(
                    runtime,
                    request.operation_id,
                    request.model_dump(mode="json"),
                    [],
                    result=result,
                    extra=[{"path": path, "content": json.dumps(value, ensure_ascii=False)}],
                ),
            )
