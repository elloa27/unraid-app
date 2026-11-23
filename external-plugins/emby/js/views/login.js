/**
 * 로그인 화면 (서버 주소 + 저장된 계정 빠른 전환 + 공개 사용자 선택)
 */
import { api } from '../api.js';
import { state, saveAccountSession, removeSavedAccount, pushView } from '../store.js';
import { escapeHtml, normalizeBaseUrl } from '../utils.js';
import { showToast, reportPluginError } from '../components/toast.js';
import { ICONS } from '../icons.js';

export function renderLoginView(appEl, view, { onLoginSuccess, bindCommonActions }) {
  const defaultUrl = view.serverUrl || api.baseUrl || state.context.activeBaseUrl || '';
  const routeText = state.context.isTailscaleActive
    ? '내장 Tailscale(tsnet) 프록시 활성'
    : '홈 네트워크 직접 연결';

  const savedAccountsHtml = state.savedAccounts.length > 0
    ? `
      <div class="saved-accounts-section">
        <div class="form-label" style="margin-bottom:8px;">저장된 계정으로 빠른 전환</div>
        <div class="saved-account-list">
          ${state.savedAccounts.map((acc, idx) => `
            <div class="saved-account-card">
              <button type="button" class="saved-account-btn" data-switch-acc="${idx}">
                <span class="saved-acc-avatar">${escapeHtml((acc.userName || '?').charAt(0).toUpperCase())}</span>
                <span class="saved-acc-info">
                  <strong>${escapeHtml(acc.userName)}${acc.isAdmin ? ' · 관리자' : ''}</strong>
                  <span>${escapeHtml(acc.baseUrl)}</span>
                </span>
              </button>
              <button type="button" class="icon-btn" data-remove-acc="${idx}" aria-label="저장된 계정 삭제">${ICONS.close}</button>
            </div>
          `).join('')}
        </div>
      </div>
    `
    : '';

  appEl.innerHTML = `
    <main class="login-page">
      <div class="login-brand">
        <div class="login-logo">
          <svg viewBox="0 0 24 24"><polygon points="7,4 20,12 7,20"/></svg>
        </div>
        <h1 class="login-title">Emby 로그인</h1>
        <p class="login-subtitle">${escapeHtml(state.context.serviceName)} · ${routeText}</p>
      </div>

      ${savedAccountsHtml}

      <form id="login-form" class="card-panel">
        ${view.error ? `<div class="error-banner" style="margin:0;"><span>${escapeHtml(view.error)}</span></div>` : ''}

        <div class="form-field">
          <label class="form-label" for="login-server">Emby 서버 URL</label>
          <input id="login-server" class="text-input" type="url" required placeholder="http://192.168.0.10:8096" value="${escapeHtml(defaultUrl)}" />
        </div>

        <div class="form-field">
          <label class="form-label" for="login-user">사용자 아이디</label>
          <input id="login-user" class="text-input" type="text" required autocomplete="username" placeholder="아이디 입력" value="${escapeHtml(view.username || '')}" />
        </div>

        <div class="form-field">
          <label class="form-label" for="login-pass">비밀번호</label>
          <input id="login-pass" class="text-input" type="password" autocomplete="current-password" placeholder="비밀번호 입력 (없으면 비워둠)" />
        </div>

        <button type="submit" class="primary-btn" ${view.submitting ? 'disabled' : ''}>
          ${view.submitting ? '로그인 중...' : 'Emby 연결 및 로그인'}
        </button>
      </form>

      <div class="login-footer-actions">
        <button class="ghost-link" data-action="open-webview">일반 웹뷰로 열기</button>
        <button class="ghost-link" data-action="close-plugin">런처로 복귀</button>
      </div>
    </main>
  `;

  bindCommonActions();

  // 저장된 계정 즉시 전환
  appEl.querySelectorAll('[data-switch-acc]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const acc = state.savedAccounts[Number(btn.dataset.switchAcc)];
      if (!acc) return;
      btn.disabled = true;
      try {
        api.configure({
          baseUrl: acc.baseUrl,
          accessToken: acc.accessToken,
          userId: acc.userId
        });
        const userObj = await api.getCurrentUser();
        state.user = userObj;
        await saveAccountSession({
          baseUrl: acc.baseUrl,
          accessToken: acc.accessToken,
          userId: acc.userId,
          userName: (userObj && userObj.Name) || acc.userName,
          isAdminFlag: Boolean(userObj && userObj.Policy && userObj.Policy.IsAdministrator)
        });
        showToast(`${state.userName}님으로 전환되었습니다.`, 'success');
        await onLoginSuccess();
      } catch (err) {
        btn.disabled = false;
        reportPluginError('SwitchAccount', '저장된 세션이 만료되었거나 연결할 수 없습니다. 다시 로그인해 주세요.', err);
      }
    });
  });

  appEl.querySelectorAll('[data-remove-acc]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const acc = state.savedAccounts[Number(btn.dataset.removeAcc)];
      if (!acc) return;
      await removeSavedAccount(acc.baseUrl, acc.userId);
      pushView(view, true);
    });
  });

  const form = document.getElementById('login-form');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const serverUrl = normalizeBaseUrl(document.getElementById('login-server').value);
    const username = document.getElementById('login-user').value.trim();
    const password = document.getElementById('login-pass').value;

    view.serverUrl = serverUrl;
    view.username = username;
    view.error = '';
    view.submitting = true;
    pushView(view, true);

    try {
      const result = await api.authenticateByName(serverUrl, username, password);
      let fullUser = result.user;
      if (!fullUser || !fullUser.Policy) {
        try {
          fullUser = await api.getCurrentUser();
        } catch (_) {}
      }
      state.user = fullUser || null;
      const isAdminFlag = Boolean(fullUser && fullUser.Policy && fullUser.Policy.IsAdministrator);

      await saveAccountSession({
        baseUrl: result.baseUrl,
        accessToken: result.accessToken,
        userId: result.userId,
        userName: result.userName,
        isAdminFlag
      });

      showToast(`${result.userName}님으로 로그인되었습니다.`, 'success');
      await onLoginSuccess();
    } catch (err) {
      view.submitting = false;
      view.error = err.message || '로그인에 실패했습니다. 서버 주소와 계정을 확인해 주세요.';
      reportPluginError('Login', view.error, err);
      pushView(view, true);
    }
  });
}
