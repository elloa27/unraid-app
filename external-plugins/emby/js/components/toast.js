/**
 * 하단 토스트 알림 및 UnraidHub 에러 로깅 유틸리티
 */

export function showToast(message, type = 'info', durationMs = 3200) {
  const root = document.getElementById('toast-root');
  if (!root) return;
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.textContent = String(message || '');
  root.appendChild(el);
  setTimeout(() => {
    el.style.opacity = '0';
    el.style.transition = 'opacity 0.2s ease';
    setTimeout(() => el.remove(), 220);
  }, durationMs);
}

export function reportPluginError(tag, userMessage, error = null) {
  const detail = error
    ? [error.message, error.detail, error.stack].filter(Boolean).join('\n')
    : userMessage;
  try {
    if (window.UnraidHub && typeof window.UnraidHub.logError === 'function') {
      window.UnraidHub.logError(tag, userMessage, detail);
    }
  } catch (_) {}
  showToast(userMessage, 'error');
}
