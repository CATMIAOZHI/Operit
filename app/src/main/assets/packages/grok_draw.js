/* METADATA
{
  "name": "grok_draw",
  "display_name": {"zh": "Grok 绘图", "en": "Grok Draw"},
  "description": {
    "zh": "使用应用内 Grok 账号登录，通过 Grok Imagine 生成或修改图片；不需要单独填写 xAI API Key。需要 SuperGrok 等带生图权限的订阅。图片保存在 Download/Operit/plugins/draw/grok_draw/draws。",
    "en": "Generate or edit images with Grok Imagine through the in-app Grok account login, without an xAI API key. Needs a subscription with image generation, such as SuperGrok. Images are saved under Download/Operit/plugins/draw/grok_draw/draws."
  },
  "category": "Draw",
  "tools": [
    {
      "name": "usage_advice",
      "description": {
        "zh": "处理生图或修图请求时，第一步调用 grok_draw:read_skill 阅读图片创作指导；读完再决定是否调用 draw_image 或 edit_image。参考图与编辑目标要分清，不要仅因启用了本包就自动生图。",
        "en": "For image generation or editing requests, first call grok_draw:read_skill. Then decide whether draw_image or edit_image fits. Distinguish reference images from edit targets; do not generate images merely because this package is enabled."
      },
      "parameters": [],
      "advice": true
    },
    {
      "name": "read_skill",
      "description": {
        "zh": "读取 Grok 绘图 Skill，了解何时生成、何时修改、怎么写提示词；不会生成图片。",
        "en": "Read the Grok Draw skill: when to generate, when to edit, how to write prompts. Does not generate an image."
      },
      "parameters": []
    },
    {
      "name": "draw_image",
      "description": {"zh": "根据提示词生成一张新图片，可传最多 5 张本地参考图，保存到本地并返回可展示的 Markdown。需要先登录 Grok 账号。", "en": "Generate a new image from a prompt, optionally with up to 5 local reference images. Saves locally and returns displayable Markdown. Needs the Grok account login."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "绘图要求", "en": "Image prompt"}},
        {"name": "aspect_ratio", "type": "string", "required": false, "description": {"zh": "画面比例：auto（默认）、1:1、16:9、9:16、4:3、3:4、3:2、2:3、2:1、1:2、19.5:9、9:19.5、20:9、9:20。只有一张参考图时按参考图比例出图", "en": "auto (default), 1:1, 16:9, 9:16, 4:3, 3:4, 3:2, 2:3, 2:1, 1:2, 19.5:9, 9:19.5, 20:9 or 9:20. With exactly one reference image the output follows that image's shape"}},
        {"name": "referenced_image_paths", "type": "array", "required": false, "description": {"zh": "仅作参考的本地 PNG、JPEG 或 WebP 图片路径数组，最多 5 张；不会覆盖原图", "en": "Up to 5 local PNG, JPEG or WebP paths used only as references; originals are kept"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output file name without path or extension"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 grok-imagine-image-2.0；也可用 grok-imagine-image-quality、grok-imagine-image", "en": "Optional, default grok-imagine-image-2.0; grok-imagine-image-quality and grok-imagine-image also work"}}
      ]
    },
    {
      "name": "edit_image",
      "description": {"zh": "按提示词修改一张本地图片，可额外传参考图，保存为新文件并返回可展示的 Markdown。原图不会被覆盖。", "en": "Edit one local image, optionally with extra reference images. Saves a new file and returns displayable Markdown; the original is kept."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "图片修改要求", "en": "Editing prompt"}},
        {"name": "image_path", "type": "string", "required": true, "description": {"zh": "要修改的本地 PNG、JPEG 或 WebP 图片路径", "en": "Local PNG, JPEG or WebP path of the image to edit"}},
        {"name": "referenced_image_paths", "type": "array", "required": false, "description": {"zh": "额外参考图的本地路径数组；加上要修改的图最多 5 张", "en": "Extra local reference image paths; at most 5 images including the edit target"}},
        {"name": "aspect_ratio", "type": "string", "required": false, "description": {"zh": "只在传了多张图时生效，取值同 draw_image；只改一张图时保持原图比例", "en": "Only used with several images, same values as draw_image; a single edited image keeps its shape"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output file name without path or extension"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 grok-imagine-image-2.0", "en": "Optional, default grok-imagine-image-2.0"}}
      ]
    }
  ]
}
*/

const IMAGEGEN_SKILL = `# 图片创作（Grok Imagine）

先判断用户是否需要位图，再决定是否使用本包的绘图工具。本指导不改变用户的原始需求。

## 判断任务

- 用户要求新图片，且没有指定要修改的原图：调用 draw_image。若有参考图，先确认它的实际本地路径，放进 referenced_image_paths，并在提示词里说明只参考哪些特征；原图不会被覆盖。
- 用户明确要求修改已有图片：找到该图片的实际本地路径，调用 edit_image 并传入 image_path；其他参考图放在 referenced_image_paths。用户上传图片但只说“参考这张图”时，不要擅自把它当成要修改的图；意图不清时先问清楚。
- SVG、图标系统、流程图、UI、HTML/CSS 或 Canvas 能更准确实现的内容，使用相应代码或设计工具。
- 画面比例用 aspect_ratio：头像、图标用 1:1，风景、横幅用 16:9，手机壁纸用 9:16，海报、人像用 2:3。拿不准就用 auto。

## 组织提示词

保留用户明确指定的主体、用途、风格、构图、文字和限制。描述充分时不要自行加人物或情节；描述笼统时只补充完成图片所必需的信息。可以按“用途与成品类型 → 主体与动作 → 构图 → 风格与光线 → 关键约束”组织。

修改时明确写出要改变的部分和必须保持的部分，例如：“只把背景改成夜晚；人物、姿势、面部和服装保持不变。”不要保证一定能像素级保留未修改区域。参考图会被自动压缩到较小尺寸，细小文字可能看不清。

## 账号与报错

本包用应用里登录的 Grok 账号，不需要 API Key。报错时如实转告用户：
- 没登录或登录失效：请用户登录 Grok 账号（Orbit 里用 orbit_vault request_account_login provider=grok 放登录卡片）。
- HTTP 403 没有生图权限：Grok 生图需要 SuperGrok 等付费订阅，免费版和 X Basic 不行。不要反复重试。
- HTTP 429：额度用完或太频繁，稍后再试。

## 执行与交付

按任务调用 draw_image 或 edit_image，只传工具实际支持的参数。一次调用出一张图；要多张就多次调用并换不同提示词。成功后把返回的 markdown 原样放进回复展示图片。`;

exports.read_skill = async () => {
  complete({ success: true, message: IMAGEGEN_SKILL });
};

function invokeGrokDraw(params) {
  const root = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof NativeInterface === "undefined" ||
      typeof NativeInterface.executeGrokDrawAsync !== "function") {
    throw new Error("当前 Operit 版本不支持 Grok 绘图");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) throw new Error("无法确认 Grok 绘图工具调用身份");
  return new Promise((resolve, reject) => {
    const callbackId = "__operit_grok_draw_" + Date.now() + "_" +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      delete root[callbackId];
      if (isError) {
        reject(new Error(String(resultJson || "Grok 绘图失败")));
        return;
      }
      try {
        resolve(JSON.parse(String(resultJson || "{}")));
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeGrokDrawAsync(callId, callbackId, JSON.stringify(params));
    } catch (error) {
      delete root[callbackId];
      reject(error);
    }
  });
}

async function runGrokDraw(params) {
  try {
    const request = Object.assign({}, params || {});
    const refs = request.referenced_image_paths;
    if (typeof refs === "string") {
      const text = refs.trim();
      if (!text) {
        delete request.referenced_image_paths;
      } else if (text.startsWith("[")) {
        try {
          request.referenced_image_paths = JSON.parse(text);
        } catch (error) {
          throw new Error("referenced_image_paths 要写成路径数组，例如 [\"/sdcard/a.png\"]");
        }
      } else {
        request.referenced_image_paths = [text];
      }
    }
    const image = await invokeGrokDraw(request);
    complete({
      success: true,
      message: image.message,
      data: image,
      hint: "展示图片时输出：" + image.markdown
    });
  } catch (error) {
    complete({
      success: false,
      message: "Grok 绘图失败：" + (error && error.message ? error.message : String(error))
    });
  }
}

exports.draw_image = async (params) => {
  const request = Object.assign({}, params || {});
  if (request.image_path) {
    complete({
      success: false,
      message: "draw_image 不接受 image_path。新图的参考图请放 referenced_image_paths；修改原图请调用 edit_image。"
    });
    return;
  }
  await runGrokDraw(request);
};

exports.edit_image = async (params) => {
  if (!params || !String(params.image_path || "").trim()) {
    complete({success: false, message: "edit_image 需要 image_path（要修改的图片的本地路径）"});
    return;
  }
  await runGrokDraw(params);
};
