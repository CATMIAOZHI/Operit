export const MIN_THINKING_QUALITY_LEVEL = 1;
export const MAX_THINKING_QUALITY_LEVEL = 5;

// 思考程度档位对应的实际 reasoning_effort 值（唯一事实来源，与 Android 端 ApiPreferences.THINKING_QUALITY_EFFORTS 保持一致）。
export const THINKING_QUALITY_EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max'];

export function clampThinkingQualityLevel(value: number): number {
  return Math.max(MIN_THINKING_QUALITY_LEVEL, Math.min(MAX_THINKING_QUALITY_LEVEL, value));
}

export function thinkingQualityEffort(level: number): string {
  const index = clampThinkingQualityLevel(level) - MIN_THINKING_QUALITY_LEVEL;
  return THINKING_QUALITY_EFFORTS[index] ?? String(level);
}

// 显示标签是档位的语义名，由实际 reasoning_effort 值派生（首字母大写）。
// 发送 reasoning_effort 的 provider 会发送与该标签一一对应的值；发送 token 预算的
// provider（OpenRouter/Qwen）按档位映射为推理预算，标签仍表示档位语义。
export function thinkingQualityLevelLabel(level: number): string {
  const effort = thinkingQualityEffort(level);
  return effort === 'xhigh' ? 'X-High' : effort.charAt(0).toUpperCase() + effort.slice(1);
}
