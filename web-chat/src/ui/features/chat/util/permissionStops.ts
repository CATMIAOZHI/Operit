/**
 * The permission choice as the app stores it: one stop that carries the level and the reuse level
 * together.
 *
 * The web chat offers the same five stops in the same order and writes the stop itself, so the two
 * places cannot disagree about which automatic-review stop is selected. Writing the level alone
 * cannot name a stop under automatic review, which is why the stop is what a client sends.
 */
export const PERMISSION_STOPS = [
  'FORBID',
  'ASK',
  'AUTO_REVIEW_STRICT',
  'AUTO_REVIEW_FAST',
  'ALLOW'
] as const;

export type PermissionStop = (typeof PERMISSION_STOPS)[number];

export const PERMISSION_STOP_LABELS: Record<PermissionStop, string> = {
  FORBID: '禁止',
  ASK: '询问',
  AUTO_REVIEW_STRICT: '自动审核 · 严格',
  AUTO_REVIEW_FAST: '自动审核 · 快速',
  ALLOW: '允许'
};

/**
 * The stop a level means on its own, which is what a snapshot from an older app carries: that app
 * names no stop, and the levels it merged into automatic review never answered a call from a stored
 * verdict, so they read as the strict stop exactly as they do in the app's own slider.
 */
const STOP_FOR_LEVEL: Record<string, PermissionStop> = {
  FORBID: 'FORBID',
  ASK: 'ASK',
  AUTO_REVIEW: 'AUTO_REVIEW_STRICT',
  WORKSPACE: 'AUTO_REVIEW_STRICT',
  WORKSPACE_REVIEWER: 'AUTO_REVIEW_STRICT',
  REVIEWER: 'AUTO_REVIEW_STRICT',
  ALLOW: 'ALLOW'
};

/** The stop the app starts a fresh install on, used while a snapshot is still on its way. */
export const DEFAULT_PERMISSION_STOP: PermissionStop = 'AUTO_REVIEW_FAST';

function isPermissionStop(value: string | undefined): value is PermissionStop {
  return value !== undefined && (PERMISSION_STOPS as readonly string[]).includes(value);
}

/** The stop to show for a snapshot, falling back to its level and then to the app's own default. */
export function resolvePermissionStop(
  permissionStop: string | undefined,
  permissionLevel: string | undefined
): PermissionStop {
  if (isPermissionStop(permissionStop)) {
    return permissionStop;
  }
  return STOP_FOR_LEVEL[permissionLevel ?? ''] ?? DEFAULT_PERMISSION_STOP;
}
