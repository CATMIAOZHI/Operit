/* METADATA
{
  "name": "grok_video",
  "display_name": {"zh": "Grok 视频", "en": "Grok Video"},
  "description": {
    "zh": "使用应用内 Grok 账号登录，通过 Grok Imagine 生成视频：文字生成、让图片动起来、按参考图生成，还能修改或续写视频；不需要单独填写 xAI API Key。需要 SuperGrok 或 X Premium+ 等带视频权限的订阅。视频保存在 Download/Operit/plugins/video/grok_video/videos。",
    "en": "Make videos with Grok Imagine through the in-app Grok account login: from text, from one image, from reference images, plus editing or extending a video. No xAI API key needed. Needs a subscription with video generation, such as SuperGrok or X Premium+. Videos are saved under Download/Operit/plugins/video/grok_video/videos."
  },
  "category": "Draw",
  "tools": [
    {
      "name": "usage_advice",
      "description": {
        "zh": "处理视频生成请求时，第一步调用 grok_video:read_skill 阅读视频创作指导；读完再决定用 generate_video、edit_video 还是 extend_video。不要仅因启用了本包就自动生成视频。",
        "en": "For video requests, first call grok_video:read_skill, then pick generate_video, edit_video or extend_video. Do not make videos merely because this package is enabled."
      },
      "parameters": [],
      "advice": true
    },
    {
      "name": "read_skill",
      "description": {
        "zh": "读取 Grok 视频 Skill，了解三种生成方式、参数范围和怎么写提示词；不会生成视频。",
        "en": "Read the Grok Video skill: the ways to make a video, parameter limits and prompt writing. Does not make a video."
      },
      "parameters": []
    },
    {
      "name": "generate_video",
      "description": {"zh": "生成一段新视频（MP4），要等 1 到几分钟。只给提示词是文字生成；给 image_path 是让这张图动起来；给 referenced_image_paths 是按参考图生成。保存到本地并返回文件路径。需要先登录 Grok 账号。", "en": "Make a new MP4 video; takes one to several minutes. Prompt only: from text. image_path: animate that image. referenced_image_paths: guided by reference images. Saves locally and returns the file path. Needs the Grok account login."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "视频内容：主体、动作、镜头和氛围", "en": "What happens: subject, motion, camera and mood"}},
        {"name": "image_path", "type": "string", "required": false, "description": {"zh": "可选，要让它动起来的本地 PNG、JPEG 或 WebP 图片路径；不能和 referenced_image_paths 同时用", "en": "Optional local PNG, JPEG or WebP image to animate; cannot be combined with referenced_image_paths"}},
        {"name": "referenced_image_paths", "type": "array", "required": false, "description": {"zh": "可选，参考图的本地路径数组，最多 7 张；用参考图时时长最多 10 秒", "en": "Optional local reference image paths, at most 7; with references the video is at most 10 seconds"}},
        {"name": "duration", "type": "number", "required": false, "description": {"zh": "时长，1 到 15 的整数秒，默认 8；用参考图时最多 10", "en": "Length in whole seconds, 1 to 15, default 8; at most 10 with reference images"}},
        {"name": "aspect_ratio", "type": "string", "required": false, "description": {"zh": "画面比例：16:9（默认）、9:16、1:1、4:3、3:4、3:2、2:3", "en": "16:9 (default), 9:16, 1:1, 4:3, 3:4, 3:2 or 2:3"}},
        {"name": "resolution", "type": "string", "required": false, "description": {"zh": "清晰度：720p（默认）或 480p", "en": "720p (default) or 480p"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output file name without path or extension"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，一般不填。文字和参考图默认 grok-imagine-video，让图片动起来默认 grok-imagine-video-1.5（它只能图生视频）", "en": "Optional, usually leave empty. Text and references default to grok-imagine-video; animating an image defaults to grok-imagine-video-1.5, which only works from an image"}}
      ]
    },
    {
      "name": "edit_video",
      "description": {"zh": "按提示词修改一段视频，保存为新的 MP4；长度沿用原视频，原视频不会被覆盖。要等 1 到几分钟。", "en": "Edit a video by prompt and save a new MP4; it keeps the source length and the original is kept. Takes one to several minutes."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "要怎么改，以及哪些要保持不变", "en": "What to change and what must stay the same"}},
        {"name": "video", "type": "string", "required": true, "description": {"zh": "要修改的视频：本地 MP4 绝对路径（小于 20 MB，优先用上次结果的 file_path），或上次结果里还没过期的 video_url", "en": "Video to edit: an absolute local MP4 path under 20 MB (prefer a previous file_path), or a previous video_url that has not expired"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output file name without path or extension"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 grok-imagine-video", "en": "Optional, default grok-imagine-video"}}
      ]
    },
    {
      "name": "extend_video",
      "description": {"zh": "接着一段视频往后续写，保存为新的 MP4。要等 1 到几分钟。", "en": "Continue a video from where it ends and save a new MP4. Takes one to several minutes."},
      "parameters": [
        {"name": "prompt", "type": "string", "required": true, "description": {"zh": "接下来发生什么", "en": "What happens next"}},
        {"name": "video", "type": "string", "required": true, "description": {"zh": "要续写的视频：本地 MP4 绝对路径（小于 20 MB，优先用上次结果的 file_path），或上次结果里还没过期的 video_url", "en": "Video to extend: an absolute local MP4 path under 20 MB (prefer a previous file_path), or a previous video_url that has not expired"}},
        {"name": "duration", "type": "number", "required": false, "description": {"zh": "续写的秒数，1 到 10 的整数，默认 6", "en": "Seconds to add, a whole number from 1 to 10, default 6"}},
        {"name": "file_name", "type": "string", "required": false, "description": {"zh": "保存文件名，不含路径和扩展名", "en": "Output file name without path or extension"}},
        {"name": "model", "type": "string", "required": false, "description": {"zh": "可选，默认 grok-imagine-video", "en": "Optional, default grok-imagine-video"}}
      ]
    }
  ]
}
*/

const VIDEO_SKILL = `# 视频创作（Grok Imagine）

先确认用户确实要一段视频，再决定用哪个工具。本指导不改变用户的原始需求。

## 选择工具

- 只有文字描述：generate_video，只填 prompt。
- 要让一张已有图片动起来（比如照片、刚画好的图）：generate_video 加 image_path，填这张图的实际本地路径。画面会以这张图为起点。
- 想让视频参考几张图里的人物、物品或风格，但不是让某张图直接动起来：generate_video 加 referenced_image_paths，最多 7 张，时长最多 10 秒。image_path 和 referenced_image_paths 不能同时用。
- 修改一段已有视频（换风格、改某个东西）：edit_video。长度沿用原视频。
- 接着一段视频往后拍：extend_video，duration 是续写的秒数（1 到 10，默认 6）。
- 修改和续写的 video 参数优先用上次结果的 file_path（本地 MP4，小于 20 MB）。video_url 是 Grok 的临时地址，过一段时间就会失效。

## 参数

- duration：1 到 15 的整数秒，默认 8。越长越慢，也越费额度；用户没要求就用默认。
- aspect_ratio：16:9（默认，横屏）、9:16（竖屏、手机）、1:1、4:3、3:4、3:2、2:3。
- resolution：720p（默认）或 480p。
- model 一般不填，工具会按用法自动选。grok-imagine-video-1.5 只能让图片动起来，不能纯文字生成。
- 只传工具实际支持的参数；没有音轨、配乐、负面提示词这些参数。

## 组织提示词

保留用户指定的主体、动作、风格、镜头和限制。按“主体与场景 → 发生的动作 → 镜头运动 → 风格与光线 → 关键约束”来写，动作要具体，比如“镜头缓慢推近，猫转头看向镜头”。让图片动起来时，描述要怎么动，不用重复描述图里已有的内容。修改视频时写清要改什么、什么保持不变。

## 等待与报错

生成要 1 到几分钟，工具会一直等到完成或失败。开始前可以先用一句话告诉用户正在生成。报错时如实转告：
- 没登录或登录失效：请用户登录 Grok 账号（Orbit 里用 orbit_vault request_account_login provider=grok 放登录卡片）。
- HTTP 403：这个账号没有视频生成权限，需要 SuperGrok 或 X Premium+ 等付费订阅。不要反复重试。
- HTTP 429：额度用完或太频繁，稍后再试。
- 等了 10 分钟还没好：工具会停止等待，可以换更短的时长再试。

## 交付

成功后返回本地 MP4 的 file_path。普通聊天里把返回的 markdown（视频嵌入写法，聊天里会直接播放）原样放进回复；在 Orbit 里要发到聊天，先 orbit_file action=import 把 file_path 导入，再 orbit_file action=send。不要只报一个设备路径。`;

exports.read_skill = async () => {
  complete({ success: true, message: VIDEO_SKILL });
};

function invokeGrokVideo(mode, params) {
  const root = typeof globalThis !== "undefined" ? globalThis : this;
  if (typeof NativeInterface === "undefined" ||
      typeof NativeInterface.executeGrokVideoAsync !== "function") {
    throw new Error("当前 Operit 版本不支持 Grok 视频");
  }
  const callId = String(root.__operitCurrentCallId || "").trim();
  if (!callId) throw new Error("无法确认 Grok 视频工具调用身份");
  return new Promise((resolve, reject) => {
    const callbackId = "__operit_grok_video_" + Date.now() + "_" +
      Math.random().toString(36).slice(2, 10);
    root[callbackId] = (resultJson, isError) => {
      delete root[callbackId];
      if (isError) {
        reject(new Error(String(resultJson || "Grok 视频生成失败")));
        return;
      }
      try {
        resolve(JSON.parse(String(resultJson || "{}")));
      } catch (error) {
        reject(error);
      }
    };
    try {
      NativeInterface.executeGrokVideoAsync(callId, callbackId, mode, JSON.stringify(params));
    } catch (error) {
      delete root[callbackId];
      reject(error);
    }
  });
}

function normalizePaths(request) {
  const refs = request.referenced_image_paths;
  if (typeof refs !== "string") return;
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

async function runGrokVideo(mode, params) {
  try {
    const request = Object.assign({}, params || {});
    normalizePaths(request);
    const video = await invokeGrokVideo(mode, request);
    complete({
      success: true,
      message: video.message,
      data: video,
      hint: "展示视频时输出：" + video.markdown
    });
  } catch (error) {
    complete({
      success: false,
      message: "Grok 视频失败：" + (error && error.message ? error.message : String(error))
    });
  }
}

exports.generate_video = async (params) => {
  if (params && String(params.video || "").trim()) {
    complete({
      success: false,
      message: "generate_video 不接受 video。修改已有视频请用 edit_video，续写请用 extend_video。"
    });
    return;
  }
  await runGrokVideo("generate", params);
};

exports.edit_video = async (params) => {
  if (!params || !String(params.video || "").trim()) {
    complete({success: false, message: "edit_video 需要 video（要修改的本地 MP4 路径，或上次结果的 video_url）"});
    return;
  }
  await runGrokVideo("edit", params);
};

exports.extend_video = async (params) => {
  if (!params || !String(params.video || "").trim()) {
    complete({success: false, message: "extend_video 需要 video（要续写的本地 MP4 路径，或上次结果的 video_url）"});
    return;
  }
  await runGrokVideo("extend", params);
};
