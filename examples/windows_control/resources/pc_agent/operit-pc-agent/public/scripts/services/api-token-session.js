const TOKEN_REF_NAMES = [
  "settingsCurrentApiTokenInput",
  "wizardCurrentApiTokenInput",
  "commandTokenInput",
  "manageTokenInput",
  "mobileTokenInput"
];

export function applyAcceptedApiToken({ token, state, refs, onMobileTokenUpdated }) {
  const normalized = String(token || "").trim();
  if (!normalized) {
    return false;
  }

  state.configAuthToken = normalized;

  for (const refName of TOKEN_REF_NAMES) {
    if (refs[refName]) {
      refs[refName].value = normalized;
    }
  }

  if (typeof onMobileTokenUpdated === "function") {
    onMobileTokenUpdated();
  }

  return true;
}
