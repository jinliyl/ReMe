"""Workspace records using existing containment, path locks and atomic publication."""

import asyncio
from contextlib import asynccontextmanager
from hashlib import sha256
import json
from uuid import uuid4

import frontmatter

from ..file_io import get_path_lock
from ...utils.async_utils import complete_in_thread
from .schema import Document


class Conflict(ValueError):
    """An operation must be reconciled with the user's newest file revision."""


def fingerprint(value):
    """Hash normalized request content, never credentials."""
    return sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


@asynccontextmanager
async def mutation(runtime):
    """One existing path lock protects application receipts and commit ordering."""
    lock = await get_path_lock(runtime.files.path("app_state/write.lock"))
    async with lock:
        yield


async def read_json(runtime, relative, default=None):
    """Read bounded JSON through the existing contained file helper."""
    return await asyncio.to_thread(runtime.files.read_json, runtime.files.path(relative), default)


async def write_json(runtime, relative, value):
    """Finish an atomic JSON write before acknowledging it."""
    await complete_in_thread(runtime.files.write_json, runtime.files.path(relative), value)


async def read_text(runtime, relative):
    """Read an existing source or return empty for an absent source."""
    try:
        return await asyncio.to_thread(runtime.files.read, runtime.files.path(relative))
    except FileNotFoundError:
        return ""


async def write_text(runtime, relative, text):
    """Serialize atomic text publication with the existing path lock."""
    path = runtime.files.path(relative)
    lock = await get_path_lock(path)
    async with lock:
        await complete_in_thread(runtime.files.write, path, text)


async def instance(runtime):
    """Initialize only durable instance identity; fresh sessions do not reset sync."""
    async with mutation(runtime):
        value = await read_json(runtime, "app_state/instance.json")
        if value is None:
            value = {"instance_id": uuid4().hex, "epoch": uuid4().hex}
            await write_json(runtime, "app_state/instance.json", value)
        return value


def document_path(kind, identifier):
    """Map a domain identity to its canonical Markdown location."""
    return f"{'tasks' if kind == 'task' else 'ideas'}/{identifier}.md"


async def document(runtime, kind, identifier):
    """Validate the source document and its path identity."""
    text = await read_text(runtime, document_path(kind, identifier))
    if not text:
        raise ValueError("document not found")
    post = frontmatter.loads(text)
    value = Document.model_validate({**post.metadata, "content": post.content})
    value.source_fingerprint = fingerprint(text)
    if value.id != identifier or value.kind != kind:
        raise ValueError("document identity does not match its workspace path")
    return value


async def documents(runtime, kind=None):
    """Read valid canonical documents without rewriting invalid sources."""
    result = []
    for current in ([kind] if kind else ["task", "idea"]):
        directory = runtime.files.path("tasks" if current == "task" else "ideas")
        for path in sorted(directory.glob("*.md")):
            try:
                result.append(await document(runtime, current, path.stem))
            except (ValueError, OSError) as exc:
                runtime.logger.warning(f"Invalid diary document {path.name}: {type(exc).__name__}")
    return result


async def receipts(runtime):
    """Read the committed operation history in sequence order."""
    directory = runtime.files.path("app_state/operations")
    result = []
    for path in sorted(directory.glob("*.json")):
        value = await read_json(runtime, path.relative_to(runtime.files.workspace).as_posix())
        if value.get("state") == "committed":
            result.append(value)
    return sorted(result, key=lambda item: item["sequence"])


async def refresh_views(runtime):
    """Views are deterministic and can be regenerated after interrupted publication."""
    items = await documents(runtime)
    todo = ["# 待办", "", "> 此文件自动生成；通过 App 或对应任务文件修改。", ""]
    tasks = sorted(
        (d for d in items if d.kind == "task"),
        key=lambda d: (
            d.status != "open",
            str(d.scheduled_at or d.scheduled_date or d.remind_at or d.due_at or d.due_date or "~"),
            d.id,
        ),
    )
    for item in tasks:
        mark = "x" if item.status == "done" else " "
        times = [
            f"{label} {value.isoformat()}"
            for label, value in (
                ("安排", item.scheduled_at or item.scheduled_date),
                ("截止", item.due_at or item.due_date),
                ("提醒", item.remind_at),
            )
            if value
        ]
        prefix = " · ".join(times) or "未排期"
        todo.append(f"- [{mark}] {prefix} · [{item.title}]({document_path('task', item.id)}) · {item.status}")
    ideas = ["# 想法", "", "> 此文件自动生成；通过 App 或对应想法文件修改。", ""]
    for item in (d for d in items if d.kind == "idea"):
        ideas.append(f"- [{item.title}]({document_path('idea', item.id)}) · {item.status}")
    await write_text(runtime, "TODO.md", "\n".join(todo) + "\n")
    await write_text(runtime, "IDEAS.md", "\n".join(ideas) + "\n")


async def commit(runtime, operation_id, request, changes, *, result=None, extra=None):
    """Persist intent, publish version-checked files, and finish a replayable receipt.

    The caller owns mutation(). Changes contain complete proposed documents and
    their expected revisions; the receipt is also the incremental sync source.
    """
    relative = f"app_state/operations/{operation_id}.json"
    prior = await read_json(runtime, relative)
    signature = fingerprint(request)
    if prior:
        if prior["fingerprint"] != signature:
            raise Conflict("operation_id was already used with different content")
        if prior["state"] == "committed":
            return prior["result"]
        intent = prior
    else:
        for change in changes:
            Document.model_validate(change["document"])
            try:
                current = await document(runtime, change["document"]["kind"], change["document"]["id"])
                revision = current.revision
                source_fingerprint = current.source_fingerprint
            except ValueError as exc:
                if str(exc) != "document not found":
                    raise
                revision = 0
                source_fingerprint = None
            if change.get("expected_fingerprint", source_fingerprint) != source_fingerprint:
                raise Conflict("source file changed since inference read it")
            change["expected_fingerprint"] = source_fingerprint
            if revision != change["expected_revision"]:
                raise Conflict("document revision changed; reload before updating")
        for pending_path in sorted(runtime.files.path("app_state/operations").glob("*.json")):
            pending = await read_json(runtime, pending_path.relative_to(runtime.files.workspace).as_posix())
            if pending["state"] == "pending":
                await replay(runtime, pending_path, pending)
        history = await receipts(runtime)
        extras = []
        for value in extra or []:
            extras.append({**value, "expected_hash": fingerprint(await read_text(runtime, value["path"]))})
        intent = {
            "id": operation_id,
            "fingerprint": signature,
            "state": "pending",
            "sequence": (history[-1]["sequence"] if history else 0) + 1,
            "changes": changes,
            "extra": extras,
            "result": result or {},
        }
        await write_json(runtime, relative, intent)
    await replay(runtime, runtime.files.path(relative), intent)
    await refresh_views(runtime)
    return intent["result"]


async def recover(runtime):
    """Repair interrupted operations before new work acquires a sequence number."""
    await instance(runtime)
    async with mutation(runtime):
        for path in sorted(runtime.files.path("app_state/operations").glob("*.json")):
            value = await read_json(runtime, path.relative_to(runtime.files.workspace).as_posix())
            if value["state"] == "pending":
                # Its validated intent, not a new model response, owns recovery.
                await replay(runtime, path, value)
        await refresh_views(runtime)


async def replay(runtime, path, intent):
    """Recover the exact request hash without fabricating a replacement request."""
    for change in intent["changes"]:
        item = Document.model_validate(change["document"])
        target = document_path(item.kind, item.id)
        lock = await get_path_lock(runtime.files.path(target))
        async with lock:
            try:
                current = await document(runtime, item.kind, item.id)
            except ValueError as exc:
                if str(exc) != "document not found":
                    raise
                current = None
            if current and current.last_operation_id == intent["id"]:
                continue
            if (current.source_fingerprint if current else None) != change["expected_fingerprint"]:
                raise Conflict("pending operation conflicts with an edited source file")
            if (current.revision if current else 0) != change["expected_revision"]:
                raise Conflict("pending operation conflicts with an edited source file")
            item.last_operation_id = intent["id"]
            await complete_in_thread(
                runtime.files.write,
                runtime.files.path(target),
                frontmatter.dumps(
                    frontmatter.Post(
                        item.content,
                        **item.model_dump(mode="json", exclude={"content"}),
                    ),
                )
                + "\n",
            )
    for value in intent["extra"]:
        target = runtime.files.path(value["path"])
        lock = await get_path_lock(target)
        async with lock:
            current = await read_text(runtime, value["path"])
            if current == value["content"]:
                continue
            if fingerprint(current) != value["expected_hash"]:
                raise Conflict("pending operation conflicts with an edited journal or state file")
            await complete_in_thread(runtime.files.write, target, value["content"])
    intent["state"] = "committed"
    await write_json(runtime, path.relative_to(runtime.files.workspace).as_posix(), intent)


async def run(runtime, run_id):
    """Read the saved state of a previously accepted input."""
    value = await read_json(runtime, f"app_state/runs/{run_id}.json")
    if value is None:
        raise ValueError("run not found")
    return value


async def update_run(runtime, run_id, *, expected_attempt=None, **changes):
    """Update only the current attempt without reviving cancelled work."""
    relative = f"app_state/runs/{run_id}.json"
    async with mutation(runtime):
        value = await run(runtime, run_id)
        if expected_attempt is not None and value.get("attempt", 0) != expected_attempt:
            return value
        if value["status"] == "cancelled" and changes.get("status") != "queued":
            return value
        value.update(changes)
        value["revision"] += 1
        await write_json(runtime, relative, value)
    return value


def reminder(item):
    """Derive a stable alarm identity from an active task schedule."""
    if item.kind != "task" or item.status != "open" or item.remind_at is None:
        return None
    return {
        "id": f"{item.id}:{item.schedule_revision}:{item.remind_at.isoformat()}",
        "task_id": item.id,
        "schedule_revision": item.schedule_revision,
        "at": item.remind_at.isoformat(),
        "title": item.title,
        "content": item.content,
    }
