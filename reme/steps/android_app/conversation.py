"""Event-runtime-owned speech, native Codex inference and validated file publication."""

import asyncio
import json
import re
import time

from pydantic_core import from_json

from ...components import R
from ...enumeration import ChunkEnum
from ...schema import collect_reply
from ...schema.proactive_agent import ProactiveRunResult
from ...utils.async_utils import finish_cleanup
from . import _state as state
from .base import AndroidStep
from .schema import DiaryDraft, Document


async def infer(step, agent_id, query, schema, *, online=False, run_id=None, attempt=None):
    """Use the existing native session owner, with read-only command permissions."""
    runtime = step.agent_runtime
    query += "\nReturn a single JSON object matching this schema (no Markdown fences):\n" + json.dumps(
        schema.model_json_schema(),
        ensure_ascii=False,
    )
    options = {
        **step.web_options(online),
        "sandbox": "read-only",
        "approval_mode": "deny_all",
        "system_prompt": step.get_prompt("system_prompt"),
        "config": {**step.web_options(online)["config"], "shell_environment_policy": {"inherit": "none"}},
    }
    session_id = await runtime.sessions.ensure(agent_id, runtime.agent_wrapper, options, runtime.now())
    blocks, last_write = {}, 0.0

    async def observe(chunk):
        nonlocal last_write
        if run_id is None or chunk.chunk_type != ChunkEnum.CONTENT or not isinstance(chunk.chunk, str):
            return
        key = chunk.block_id or chunk.message_id or "answer"
        blocks[key] = blocks.get(key, "") + chunk.chunk if chunk.event == "delta" else chunk.chunk
        try:
            value = from_json(blocks[key], allow_partial="trailing-strings")
        except ValueError:
            return
        if isinstance(value, dict) and isinstance(value.get("answer"), str) and time.monotonic() - last_write > 0.4:
            await state.update_run(
                runtime,
                run_id,
                status="replying",
                preview=value["answer"],
                expected_attempt=attempt,
            )
            last_write = time.monotonic()

    async def generate():
        return await collect_reply(
            runtime.agent_wrapper.reply_stream(
                query,
                resume=session_id,
                output_schema=schema.model_json_schema(),
                **options,
            ),
            on_chunk=observe,
        )

    async def cancelled():
        while True:
            current = await state.run(runtime, run_id)
            if current["status"] == "cancelled" or current.get("attempt", 0) != attempt:
                return
            await asyncio.sleep(0.25)

    task = asyncio.create_task(generate())
    watch = asyncio.create_task(cancelled()) if run_id else None
    tasks = [task, *([watch] if watch else [])]
    try:
        async with asyncio.timeout(float(step.kwargs.get("timeout_seconds", 300))):
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            if watch in done:
                return None, session_id
            reply = await task
        if reply["status"] != "completed":
            raise RuntimeError("The model did not complete this turn: " + str(reply.get("error") or reply["status"]))
        return schema.model_validate(reply["structured_output"] or json.loads(reply["result"])), reply["session_id"]
    finally:
        for child in tasks:
            child.cancel()
        await finish_cleanup(asyncio.gather(*tasks, return_exceptions=True))


@R.register("android_run_step")
class RunStep(AndroidStep):
    """Process saved inputs using the existing serial Agent runtime."""

    async def execute(self):
        agent_id = self.context.get("agent_id")
        if agent_id == "idea_research":
            from .research import process_research

            async def callback(agent, events):
                return await process_research(self, agent, events)

        elif agent_id == "diary":
            callback = self.process
        else:
            raise ValueError("unknown Android application agent")
        self.answer(await self.agent_runtime.run(agent_id, callback))
        return self.context.response

    async def process(self, _agent, events):
        """Isolate failures per input while preserving runtime supervision."""
        session_id = ""
        for event in events:
            run_id = event.get("metadata", {}).get("run_id")
            if not run_id:
                continue
            value = await state.run(self.agent_runtime, run_id)
            if value["status"] in {"completed", "cancelled", "failed"}:
                session_id = value.get("session_id", session_id)
                continue
            try:
                session_id = await self.process_input(value)
            except Exception as exc:
                # Provider error bodies can contain request data; keep only classification here.
                await state.update_run(
                    self.agent_runtime,
                    run_id,
                    status="failed",
                    error=type(exc).__name__,
                    expected_attempt=value.get("attempt", 0),
                )
                self.logger.warning(f"Diary run {run_id} failed: {type(exc).__name__}")
        return ProactiveRunResult(notifications=[]), session_id

    async def process_input(self, value):
        """Transcribe, infer and commit one durable input attempt."""
        runtime = self.agent_runtime
        run_id = value["id"]
        attempt = value.get("attempt", 0)
        text = value["text"]
        if value["kind"] == "audio" and not text:
            await state.update_run(runtime, run_id, status="transcribing", expected_attempt=attempt)
            transcript_path = value["resource"] + "/transcript.md"
            text = await state.read_text(runtime, transcript_path)
            if not text:
                audio = await asyncio.to_thread(runtime.files.path(value["resource"] + "/audio.wav").read_bytes)
                result = await self.asr.transcribe(audio, audio_format="wav", sample_rate=16000)
                text = result.text.strip()
                if not text:
                    raise ValueError("No recognizable speech")
                await state.write_text(runtime, transcript_path, text + "\n")
            await state.update_run(runtime, run_id, text=text.strip(), expected_attempt=attempt)
        latest = await state.run(runtime, run_id)
        if latest["status"] == "cancelled" or latest.get("attempt", 0) != attempt:
            return ""
        await state.update_run(runtime, run_id, status="thinking", error=None, preview="", expected_attempt=attempt)
        snapshot = await state.documents(runtime)
        context = [item.model_dump(mode="json") for item in snapshot]
        recent = []
        for path in sorted(runtime.files.path("daily").glob("*/journal.md"), reverse=True)[:10]:
            recent.append(await state.read_text(runtime, path.relative_to(self.workspace_path).as_posix()))
        query = self.prompt_format(
            "query",
            captured_at=value["captured_at"],
            timezone=value["timezone"],
            text=text,
            source_path=value["resource"] + "/input.md",
            documents=json.dumps(context, ensure_ascii=False),
            history="\n\n".join(recent)[-60000:],
            target=json.dumps(
                {
                    "kind": value.get("target_kind"),
                    "id": value.get("target_id"),
                },
            ),
        )
        if value.get("draft_attempt") == value.get("attempt", 0) and value.get("draft"):
            draft = DiaryDraft.model_validate(value["draft"])
            snapshot = [Document.model_validate(item) for item in value["base_documents"]]
            for item in snapshot:
                item.source_fingerprint = value["base_fingerprints"][f"{item.kind}/{item.id}"]
            session_id = value["session_id"]
        else:
            draft, session_id = await infer(
                self,
                "diary",
                query,
                DiaryDraft,
                online=True,
                run_id=run_id,
                attempt=attempt,
            )
            if draft is None:
                return session_id
            await state.update_run(
                runtime,
                run_id,
                expected_attempt=attempt,
                draft=draft.model_dump(mode="json"),
                draft_attempt=value.get("attempt", 0),
                base_documents=[item.model_dump(mode="json") for item in snapshot],
                base_fingerprints={f"{item.kind}/{item.id}": item.source_fingerprint for item in snapshot},
                session_id=session_id,
            )
        if not await self.publish(value, text, draft, snapshot):
            return session_id
        await state.update_run(
            runtime,
            run_id,
            status="completed",
            expected_attempt=attempt,
            answer=draft.answer,
            preview=draft.answer,
            message_id=f"message_{state.fingerprint(run_id)[:24]}",
            session_id=session_id,
        )
        return session_id

    async def publish(self, value, text, draft, snapshot):
        """Publish validated drafts only against the captured source versions."""
        runtime = self.agent_runtime
        run_id = value["id"]
        existing = {item.id: item for item in snapshot}
        changes = []
        for kind, proposals in [("task", draft.tasks), ("idea", draft.ideas)]:
            for index, proposed in enumerate(proposals):
                identifier = proposed.id or f"{kind}_{state.fingerprint([run_id, kind, index])[:20]}"
                current = existing.get(identifier)
                if proposed.id and (current is None or current.kind != kind):
                    raise ValueError("model referred to an unknown document")
                if current and proposed.expected_revision not in (None, current.revision):
                    raise state.Conflict("model used a stale document revision")
                item = (
                    current.model_copy(deep=True)
                    if current
                    else Document(
                        id=identifier,
                        kind=kind,
                        title=proposed.title,
                        status="open" if kind == "task" else "collecting",
                        timezone=value["timezone"],
                    )
                )
                item.title, item.content = proposed.title, proposed.content
                if current:
                    if kind == "idea" and item.status in {"ready_for_discussion", "researching"}:
                        item.status = "collecting"
                    item.revision += 1
                    item.input_revision += 1
                item.source_ids = list(dict.fromkeys([*item.source_ids, run_id]))
                has_clock = re.search(
                    r"(?:[0-9]{1,2}\s*(?:[:：][0-9]{2}|点|时)|[一二两三四五六七八九十]{1,3}\s*(?:点|时))",
                    proposed.time_evidence,
                )
                if kind == "task" and has_clock and proposed.time_evidence in text:
                    # A model suggestion without an exact user quote cannot register an alarm.
                    times = (proposed.scheduled_at, proposed.due_at, proposed.remind_at)
                    if current and times != (item.scheduled_at, item.due_at, item.remind_at):
                        item.schedule_revision += 1
                    item.scheduled_at, item.due_at, item.remind_at = times
                related_kind = "idea" if kind == "task" else "task"
                related = [
                    identifier
                    for identifier in proposed.related_ids
                    if identifier in existing and existing[identifier].kind == related_kind
                ]
                if kind == "task":
                    item.related_ideas = list(dict.fromkeys([*item.related_ideas, *related]))
                else:
                    item.related_tasks = list(dict.fromkeys([*item.related_tasks, *related]))
                changes.append(
                    {
                        "expected_revision": current.revision if current else 0,
                        "expected_fingerprint": current.source_fingerprint if current else None,
                        "document": item.model_dump(mode="json"),
                    },
                )
        async with state.mutation(runtime):
            latest = await state.run(runtime, run_id)
            if latest["status"] == "cancelled" or latest.get("attempt", 0) != value.get("attempt", 0):
                return False
            day, clock = value["captured_at"][:10], value["captured_at"][11:16]
            journal_path = f"daily/{day}/journal.md"
            journal = await state.read_text(runtime, journal_path) or f"# {day}\n\n"
            start, end = f"<!-- capture:{run_id}:start -->", f"<!-- capture:{run_id}:end -->"
            links = [
                f"[{entry['document']['title']}](../../"
                f"{state.document_path(entry['document']['kind'], entry['document']['id'])})"
                for entry in changes
            ]
            prior_days = set()
            for entry in changes:
                for source_id in entry["document"]["source_ids"]:
                    if source_id == run_id:
                        continue
                    source = await state.read_json(runtime, f"app_state/runs/{source_id}.json")
                    if source and source["captured_at"][:10] != day:
                        prior_days.add(source["captured_at"][:10])
            links.extend(f"[历史日记 {prior}](../{prior}/journal.md)" for prior in sorted(prior_days))
            related_links = "\n\n关联：" + " · ".join(links) if links else ""
            section = (
                f"{start}\n## {clock}\n\n{text}\n\n### 整理\n\n{draft.journal}\n\n"
                f"[原始记录](../../{value['resource']}/input.md){related_links}\n{end}"
            )
            if start in journal and end in journal:
                journal = re.sub(re.escape(start) + r".*?" + re.escape(end), lambda _: section, journal, flags=re.S)
            else:
                journal = journal.rstrip() + "\n\n" + section + "\n"
            result = {"documents": [entry["document"]["id"] for entry in changes]}
            await state.commit(
                runtime,
                f"organize_{state.fingerprint([run_id, value.get('attempt', 0)])[:24]}",
                {"run_id": run_id, "attempt": value.get("attempt", 0)},
                changes,
                result=result,
                extra=[{"path": journal_path, "content": journal}],
            )
        await state.update_run(runtime, run_id, documents=result["documents"], expected_attempt=value.get("attempt", 0))
        return True
