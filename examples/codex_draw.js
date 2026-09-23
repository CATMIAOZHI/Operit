/* METADATA
{
  "name": "codex_draw",
  "display_name": {"zh": "Codex 绘图", "en": "Codex Draw"},
  "description": {
    "zh": "使用应用内 Codex 登录生成或编辑图片；不需要单独填写 API Key。图片保存在 Download/Operit/plugins/draw/codex_draw/draws。",
    "en": "Generate or edit images with the in-app Codex login, without another API key. Images are saved under Download/Operit/plugins/draw/codex_draw/draws."
  },
  "category": "Draw",
  "tools": [
    {
      "name": "usage_advice",
      "description": {
        "zh": "处理生图或修图请求时，第一步调用 codex_draw:read_skill 阅读图片创作指导；读完再决定是否调用 draw_image 或 edit_image。参考图与编辑目标要分清，不要仅因启用了本包就自动生图。",
        "en": "For image generation or editing requests, first call codex_draw:read_skill. Then decide whether draw_image or edit_image is appropriate. Distinguish reference images from edit targets; do not generate images merely because this package is enabled."
      },
      "parameters": [],
      "advice": true
    },
    {
      "name": "read_skill",
      "description": {
        "zh": "读取 Codex 绘图 Skill，了解何时生成、何时编辑及提示词和编辑约束；不会生成图片。",
        "en": "Read the Codex Draw skill to decide when to generate or edit and how to write prompts and editing constraints. Does not generate an image."
      },
      "parameters": []
    },
    {
      "name": "draw_image",
      "description": {"zh": "根据提示词生成一张新图片，可传入最多 5 张本地参考图，保存到本地并返回可展示的 Markdown。需要先在模型设置中登录 Codex。", "en": "Generate a new image from a prompt, optionally using up to 5 local reference images. Requires the Codex login in model settings."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "绘图要求", "en": "Image prompt"}},
        {"name": "referenced_image_paths", "type": "array", "required": false, "description": {"zh": "仅作参考的本地 PNG、JPEG 或 WebP 图片路径数组，最多 5 张；不会覆盖原图", "en": "Up to 5 local PNG, JPEG, or WebP paths used only as references; original files are preserved"}},
        {"name": "size", "type": "string", "required": false, "description": {"zh": "1024x1024、1024x1536、1536x1024 或 auto", "en": "1024x1024, 1024x1536, 1536x1024, or auto"}},
        {"name": "quality", "type": "string", "required": false, "description": {"zh": "auto、low、medium 或 high", "en": "auto, low, medium, or high"}},
        {"name": "background", "type": "string", "required": false, "description": {"zh": "auto、opaque 或 transparent", "en": "auto, opaque, or transparent"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output filename without path or extension"}},
        {"name": "chat_model", "type": "string", "required": false, "description": {"zh": "可选承载模型；默认 gpt-5.5，若服务端不支持可指定当前 Codex 模型", "en": "Optional carrier model, default gpt-5.5; change if the server rejects it"}}
      ]
    },
    {
      "name": "edit_image",
      "description": {"zh": "按提示词编辑一张本地图片，可额外传入参考图，保存为新文件并返回可展示的 Markdown。", "en": "Edit one local image using optional reference images, save a new file, and return displayable Markdown."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "图片修改要求", "en": "Image editing prompt"}},
        {"name": "image_path", "type": "string", "required": true, "description": {"zh": "本地 PNG、JPEG 或 WebP 图片路径", "en": "Local PNG, JPEG, or WebP path"}},
        {"name": "referenced_image_paths", "type": "array", "required": false, "description": {"zh": "额外参考图的本地路径数组；加上编辑目标最多 5 张", "en": "Additional local reference image paths; at most 5 images including the edit target"}},
        {"name": "size", "type": "string", "required": false, "description": {"zh": "1024x1024、1024x1536、1536x1024 或 auto", "en": "1024x1024, 1024x1536, 1536x1024, or auto"}},
        {"name": "quality", "type": "string", "required": false, "description": {"zh": "auto、low、medium 或 high", "en": "auto, low, medium, or high"}},
        {"name": "background", "type": "string", "required": false, "description": {"zh": "auto、opaque 或 transparent", "en": "auto, opaque, or transparent"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output filename without path or extension"}},
        {"name": "chat_model", "type": "string", "required": false, "description": {"zh": "可选承载模型；默认 gpt-5.5", "en": "Optional carrier model, default gpt-5.5"}}
      ]
    }
  ]
}
*/

const IMAGEGEN_SKILL = `# 图片创作

先判断用户是否需要位图，再决定是否使用本包的绘图工具。本指导不改变用户的原始需求。

## 判断任务

- 用户要求新图片，且没有指定要修改的原图：调用 draw_image。若有参考图，先确认它的实际本地路径，作为 referenced_image_paths 传入，并在提示词中说明只参考哪些特征；原图不会被覆盖。
- 用户明确要求修改已有图片：找到该图片的实际本地路径，调用 edit_image 并传入 image_path；其他参考图可放在 referenced_image_paths。用户上传图片但只说“参考这张图”时，不要擅自把它当成编辑目标；意图不清时先问清楚。
- SVG、图标系统、流程图、UI、HTML/CSS 或 Canvas 能更准确实现的内容，使用相应代码或设计工具。
- 没有应用内 Codex 登录时，绘图工具会明确报错；不要把 read_skill 当成生成结果。

## 组织提示词

保留用户明确指定的主体、用途、风格、构图、文字和限制。描述充分时不要自行加人物或情节；描述笼统时只补充完成图片所必需的信息。可以按“用途与成品类型 → 主体与动作 → 构图 → 风格与光线 → 关键约束”组织。

编辑时明确写出要改变的部分，以及必须保持的部分。例如：“只把背景改成夜晚；人物、姿势、面部和服装保持不变。”不要保证模型一定能像素级保留未修改区域。参考图片与编辑目标必须分清；两种输入都需要可读取的本地图片路径。拿不到图片路径时不要假装已传给绘图工具。

## 执行与交付

按任务调用 draw_image 或 edit_image，只传工具实际支持的参数。调用后检查结果；能查看成图时，检查主体、构图、文字及编辑约束。失败时说明真实错误；成功时向用户展示图片。`;

exports.read_skill = async () => {
  complete({ success: true, message: IMAGEGEN_SKILL });
};

function invokeCodexDraw(params) {
  const root = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof NativeInterface === "undefined" ||
      typeof NativeInterface.executeCodexDrawAsync !== "function") {
    throw new Error("当前 Operit 版本不支持 Codex 绘图");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) throw new Error("无法确认 Codex 绘图工具调用身份");
  return new Promise((resolve, reject) => {
    const callbackId = "__operit_codex_draw_" + Date.now() + "_" +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      delete root[callbackId];
      if (isError) {
        reject(new Error(String(resultJson || "Codex 绘图失败")));
        return;
      }
      try {
        resolve(JSON.parse(String(resultJson || "{}")));
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeCodexDrawAsync(callId, callbackId, JSON.stringify(params));
    } catch (error) {
      delete root[callbackId];
      reject(error);
    }
  });
}

async function runCodexDraw(params) {
  try {
    const image = await invokeCodexDraw(params || {});
    complete({
      success: true,
      message: image.message,
      data: image,
      hint: "展示图片时输出：" + image.markdown
    });
  } catch (error) {
    complete({
      success: false,
      message: "Codex 绘图失败：" + (error && error.message ? error.message : String(error))
    });
  }
}

exports.draw_image = async (params) => {
  const request = Object.assign({}, params || {});
  if (request.image_path) {
    complete({
      success: false,
      message: "draw_image 不接受 image_path。新图请传 referenced_image_paths；修改原图请调用 edit_image。"
    });
    return;
  }
  await runCodexDraw(request);
};
exports.edit_image = async (params) => {
  if (!params || !String(params.image_path || "").trim()) {
    complete({success: false, message: "edit_image 需要 image_path"});
    return;
  }
  await runCodexDraw(params);
};
