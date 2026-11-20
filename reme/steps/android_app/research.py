"""Bounded proactive research using existing scheduled Agent execution."""

from . import _state as state
from .conversation import infer
from .schema import ResearchDraft


async def process_research(step, _agent, _events):
    """Research one new idea version within the daily commit budget."""
    from ...schema.proactive_agent import ProactiveRunResult

    runtime = step.agent_runtime
    day = runtime.now().date().isoformat()
    candidates = [
        item
        for item in await state.documents(runtime, "idea")
        if item.status not in {"paused", "archived", "adopted"} and item.researched_input_revision < item.input_revision
    ]
    session_id = ""
    if candidates:
        idea = candidates[0]
        async with state.mutation(runtime):
            history = await state.receipts(runtime)
            spent = sum(item["id"].startswith(f"research_attempt_{day}_") for item in history)
            if spent >= int(step.kwargs.get("max_research_per_day", 3)):
                return ProactiveRunResult(notifications=[]), ""
            await state.commit(
                runtime,
                f"research_attempt_{day}_{spent + 1}",
                {"idea_id": idea.id, "input_revision": idea.input_revision},
                [],
                result={"idea_id": idea.id, "input_revision": idea.input_revision, "status": "started"},
            )
        query = (
            "预研以下想法，按需查找可靠来源。区分事实和推断，提出可供用户讨论的问题。"
            "保留用户已确认决定，不创建任务，不修改文件。最多查询三次网页，失败的链接不再重试。"
            "用已找到的事实和明确标记的推断完成初步方案，不必穷尽资料。\n" + idea.model_dump_json()
        )
        # Research uses its own prompt while sharing all native-session infrastructure.
        step.prompt.load_prompt_dict(
            {
                "system_prompt": "Research a personal idea with reliable sources. "
                "Return JSON matching the output schema. Do not read credentials or modify files.",
                "system_prompt_zh": "围绕用户想法预研，按需联网，保留证据和待讨论问题，"
                "按 schema 返回 JSON，不读取凭据或修改文件。",
            },
        )
        draft, session_id = await infer(step, "idea_research", query, ResearchDraft, online=True)
        current = idea.model_copy(deep=True)
        current.status = "ready_for_discussion"
        current.revision += 1
        current.researched_input_revision = idea.input_revision
        report = (
            f"# {idea.title}\n\n{draft.summary}\n\n## 来源\n\n"
            + "\n".join(f"- {url}" for url in draft.sources if url.startswith(("https://", "http://")))
            + "\n\n## 待讨论\n\n"
            + "\n".join(f"- {text}" for text in draft.questions)
            + "\n"
        )
        current.content = current.content.split("\n## AI 预研\n")[0] + "\n\n## AI 预研\n\n" + draft.summary
        links = [url for url in draft.sources if url.startswith(("https://", "http://"))]
        if links:
            current.content += "\n\n## 参考资料\n\n" + "\n".join(f"- [{url}]({url})" for url in links)
        if draft.questions:
            current.content += "\n\n## 待讨论\n\n" + "\n".join(f"- {text}" for text in draft.questions)
        async with state.mutation(runtime):
            await state.commit(
                runtime,
                f"research_{day}_{state.fingerprint([idea.id, idea.input_revision])[:16]}",
                {"idea": idea.id, "input_revision": idea.input_revision},
                [
                    {
                        "expected_revision": idea.revision,
                        "expected_fingerprint": idea.source_fingerprint,
                        "document": current.model_dump(mode="json"),
                    },
                ],
                extra=[{"path": f"research/{idea.id}/{day}_{idea.input_revision}.md", "content": report}],
            )
    return ProactiveRunResult(notifications=[]), session_id
