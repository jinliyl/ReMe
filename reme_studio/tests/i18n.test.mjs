import assert from "node:assert/strict";
import test from "node:test";
import { translate } from "../app/i18n.ts";

test("translations support Chinese, English, and interpolation", () => {
  assert.equal(translate("zh", "openAgentChat"), "打开 Agent 对话");
  assert.equal(translate("en", "openAgentChat"), "Open Agent chat");
  assert.equal(
    translate("en", "openingFile", { path: "daily/today.md" }),
    "Opening daily/today.md",
  );
});
