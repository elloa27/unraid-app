/**
 * Emby 설정 및 관리자 대시보드 화면
 * - 탭 1: 사용자 설정 (서버 URL, 계정 정보, 계정 전환/로그아웃, 재생 및 UI 환경설정, 일반 웹뷰 바로가기)
 * - 탭 2: 서버 관리 (Policy.IsAdministrator === true일 때만 표시)
 *   · 전체/개별 라이브러리 스캔
 *   · 활성 세션 모니터링 (GET /Sessions)
 *   · 예약 작업 조회·실행·중지 (GET /ScheduledTasks)
 *   · 서버 정보 및 재시작 제어 (GET /System/Info, POST /System/Restart)
 */
import { api } from '../api.js';
import {
  STORAGE_KEYS,
  state,
  isAdmin,
  savePref,
  resolveActiveBaseUrl,
  saveAccountSession,
  clearActiveSession,
  triggerRender
} from '../store.js';
import { ICONS } from '../icons.js';
import {
  escapeHtml,
  normalizeBaseUrl,
  formatTimestamp,
  formatDateTime,
  formatEpisodeCode,
  TICKS_PER_SECOND
} from '../utils.js';
import { renderTopBar } from '../components/cards.js';
import { openSheet, openConfirmModal } from '../components/modal.js';
import { showToast, reportPluginError } from '../components/toast.js';

export function createSettingsViewState(initialTab = 'user') {
  return {
    type: 'settings',
    activeTab: (initialTab === 'admin' && isAdmin()) ? 'admin' : 'user',
    adminLoading: false,
    adminError: '',
    systemInfo: null,
    sessions: [],
    tasks: [],
    libraries: []
  };
}

export async function loadAdminDashboardData(view, silent = false) {
  if (!isAdmin()) return;
  if (!silent) {
    view.adminLoading = true;
    view.adminError = '';
    triggerRender();
  }
  try {
    const [systemInfo, sessions, tasks, libraries] = await Promise.all([
      api.getSystemInfo(),
      api.getActiveSessions(),
      api.getScheduledTasks(),
      api.getLibraries().catch(() => state.homeData.libraries || [])
    ]);
    view.systemInfo = systemInfo;
    view.sessions = sessions;
    view.tasks = tasks;
    view.libraries = libraries;
    view.adminLoading = false;
    view.adminError = '';
  } catch (err) {
    view.adminLoading = false;
    view.adminError = err.message || '서버 관리 정보를 불러오지 못했습니다.';
    reportPluginError('AdminDashboard', view.adminError, err);
  }
  triggerRender();
}

function openAccountSwitcherSheet({ onSwitched, onNewLogin }) {
  openSheet({
    title: '계정 전환',
    subtitle: '저장된 세션 선택 또는 새 계정으로 로그인',
    bodyHtml: `
      <div class="form-stack">
        ${state.savedAccounts.length > 0 ? `
          <div class="sheet-group-label">저장된 계정</div>
          <div class="saved-account-list">
            ${state.savedAccounts.map((acc, idx) => {
              const isCurrent = acc.userId === api.userId && acc.baseUrl === api.baseUrl;
              return `
                <button type="button" class="saved-account-btn full-card ${isCurrent ? 'is-current' : ''}" data-pick-acc="${idx}">
                  <span class="saved-acc-avatar">${escapeHtml((acc.userName || '?').charAt(0).toUpperCase())}</span>
                  <span class="saved-acc-info">
                    <strong>${escapeHtml(acc.userName)}${acc.isAdmin ? ' · 관리자' : ''}</strong>
                    <span>${escapeHtml(acc.baseUrl)}</span>
                  </span>
                  ${isCurrent ? '<span class="current-tag">현재</span>' : ''}
                </button>
              `;
            }).join('')}
          </div>
        ` : '<p class="empty-inline">저장된 다른 계정이 없습니다.</p>'}
        <button type="button" class="primary-btn" id="switcher-new-login">다른 아이디로 로그인</button>
      </div>
    `,
    onMount: (el, close) => {
      el.querySelector('#switcher-new-login').addEventListener('click', () => {
        close();
        onNewLogin();
      });

      el.querySelectorAll('[data-pick-acc]').forEach((btn) => {
        btn.addEventListener('click', async () => {
          const acc = state.savedAccounts[Number(btn.dataset.pickAcc)];
          if (!acc) return;
          if (acc.userId === api.userId && acc.baseUrl === api.baseUrl) {
            close();
            return;
          }
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
            close();
            showToast(`${state.userName}님으로 전환되었습니다.`, 'success');
            onSwitched();
          } catch (err) {
            btn.disabled = false;
            reportPluginError('SwitchAccount', '세션 전환에 실패했습니다. 다시 로그인해 주세요.', err);
          }
        });
      });
    }
  });
}

function renderUserSettingsTab() {
  const routeText = state.context.isTailscaleActive ? 'Tailscale 프록시 경유' : '홈 네트워크 직접 연결';
  const adminBadge = isAdmin() ? '<span class="admin-badge">관리자</span>' : '<span class="user-badge">일반 사용자</span>';
  const prefs = state.prefs;

  return `
    <div class="settings-section">
      <h3 class="settings-section-title">계정 및 연결 정보</h3>
      <div class="card-panel">
        <div class="account-profile-row">
          <div class="saved-acc-avatar lg">${escapeHtml((state.userName || '?').charAt(0).toUpperCase())}</div>
          <div class="account-profile-meta">
            <div class="account-name-row">
              <strong>${escapeHtml(state.userName || '알 수 없음')}</strong>
              ${adminBadge}
            </div>
            <p class="card-sub">${routeText}</p>
          </div>
        </div>

        <div class="info-kv">
          <div class="info-kv-row">
            <span class="info-kv-key">현재 활성 주소</span>
            <span class="info-kv-val">${escapeHtml(api.baseUrl || '-')}</span>
          </div>
          <div class="info-kv-row">
            <span class="info-kv-key">플러그인 버전</span>
            <span class="info-kv-val">v${escapeHtml(state.context.pluginVersion || '2.0.0')}</span>
          </div>
        </div>

        <div class="form-field">
          <label class="form-label" for="settings-server-url">Emby 서버 URL 변경</label>
          <input id="settings-server-url" class="text-input" type="url" value="${escapeHtml(api.baseUrl || '')}" placeholder="http://192.168.0.10:8096" />
        </div>

        <div class="cta-row">
          <button type="button" class="primary-btn" id="settings-save-url">주소 저장</button>
          <button type="button" class="secondary-btn" id="settings-reset-url">자동 주소 복원</button>
        </div>

        <div class="cta-row">
          <button type="button" class="secondary-btn" id="settings-switch-account">다른 계정 전환</button>
          <button type="button" class="danger-btn" id="settings-logout">로그아웃</button>
        </div>
      </div>
    </div>

    <div class="settings-section">
      <h3 class="settings-section-title">재생 및 UI 환경설정</h3>
      <div class="card-panel">
        <label class="switch-row">
          <div class="switch-text">
            <span class="switch-title">다음 화 자동 재생</span>
            <span class="switch-desc">에피소드 시청 완료 시 5초 카운트다운 후 다음 화를 자동으로 재생합니다.</span>
          </div>
          <input id="pref-auto-play-next" type="checkbox" class="toggle-switch" ${prefs.autoPlayNext ? 'checked' : ''} />
        </label>

        <div class="form-field">
          <label class="form-label" for="pref-audio-lang">기본 선호 오디오 언어</label>
          <select id="pref-audio-lang" class="select-input">
            <option value="kor" ${prefs.preferredAudioLang === 'kor' ? 'selected' : ''}>한국어 (KOR)</option>
            <option value="eng" ${prefs.preferredAudioLang === 'eng' ? 'selected' : ''}>영어 (ENG)</option>
            <option value="jpn" ${prefs.preferredAudioLang === 'jpn' ? 'selected' : ''}>일본어 (JPN)</option>
            <option value="auto" ${prefs.preferredAudioLang === 'auto' ? 'selected' : ''}>자동 (서버 기본값)</option>
          </select>
        </div>

        <div class="form-field">
          <label class="form-label" for="pref-sub-lang">기본 선호 자막 언어</label>
          <select id="pref-sub-lang" class="select-input">
            <option value="kor" ${prefs.preferredSubLang === 'kor' ? 'selected' : ''}>한국어 (KOR)</option>
            <option value="eng" ${prefs.preferredSubLang === 'eng' ? 'selected' : ''}>영어 (ENG)</option>
            <option value="jpn" ${prefs.preferredSubLang === 'jpn' ? 'selected' : ''}>일본어 (JPN)</option>
            <option value="auto" ${prefs.preferredSubLang === 'auto' ? 'selected' : ''}>자동 (기본 트랙)</option>
            <option value="none" ${prefs.preferredSubLang === 'none' ? 'selected' : ''}>자막 끄기</option>
          </select>
        </div>

        <div class="form-field">
          <span class="form-label">라이브러리 그리드 열 수</span>
          <div class="segmented-tabs">
            ${[2, 3, 4].map((n) => `
              <button type="button" class="seg-tab ${prefs.gridCols === n ? 'active' : ''}" data-grid-cols="${n}">${n}열</button>
            `).join('')}
          </div>
        </div>
      </div>
    </div>

    <div class="settings-section">
      <h3 class="settings-section-title">앱 바로가기</h3>
      <div class="card-panel">
        <div class="cta-row">
          <button type="button" class="secondary-btn" data-action="open-webview">
            <span class="mini-icon">${ICONS.externalLink}</span>
            <span>일반 웹뷰로 열기</span>
          </button>
          <button type="button" class="secondary-btn" data-action="close-plugin">런처로 복귀</button>
        </div>
      </div>
    </div>
  `;
}

function renderAdminDashboardTab(view) {
  if (view.adminLoading && !view.systemInfo) {
    return `
      <div class="center-state">
        <div class="spinner"></div>
        <p class="boot-text">서버 관리 데이터를 불러오는 중...</p>
      </div>
    `;
  }

  const info = view.systemInfo || {};
  const playingSessions = (view.sessions || []).filter((s) => s && s.NowPlayingItem);
  const idleCount = Math.max(0, (view.sessions || []).length - playingSessions.length);
  const libraries = view.libraries || [];
  const tasks = (view.tasks || []).filter((t) => t && !t.IsHidden);

  const playMethodLabel = (session) => {
    const pm = session.PlayState && session.PlayState.PlayMethod;
    if (pm === 'Transcode' || session.TranscodingInfo) return { text: 'Transcode', cls: 'warn' };
    if (pm === 'DirectStream') return { text: 'Direct Stream', cls: 'blue' };
    return { text: 'Direct Play', cls: 'ok' };
  };

  const taskStateLabel = (st) => {
    if (st === 'Running') return { text: '실행 중', cls: 'running' };
    if (st === 'Cancelling') return { text: '중지 중', cls: 'warn' };
    return { text: '대기', cls: 'idle' };
  };

  return `
    ${view.adminError ? `<div class="error-banner" style="margin:12px 16px 0;"><span>${escapeHtml(view.adminError)}</span></div>` : ''}

    <!-- 1. 서버 정보 및 제어 -->
    <div class="settings-section">
      <div class="section-header-inline">
        <h3 class="settings-section-title">서버 정보 및 제어</h3>
        <button type="button" class="section-action-btn" id="admin-refresh-all">
          <span class="mini-icon">${ICONS.refresh}</span>
          <span>새로고침</span>
        </button>
      </div>
      <div class="card-panel">
        <div class="info-kv">
          <div class="info-kv-row">
            <span class="info-kv-key">서버 이름</span>
            <span class="info-kv-val">${escapeHtml(info.ServerName || '-')}</span>
          </div>
          <div class="info-kv-row">
            <span class="info-kv-key">Emby 버전</span>
            <span class="info-kv-val">${escapeHtml(info.Version || '-')}</span>
          </div>
          <div class="info-kv-row">
            <span class="info-kv-key">운영체제</span>
            <span class="info-kv-val">${escapeHtml(info.OperatingSystemDisplayName || info.OperatingSystem || '-')}</span>
          </div>
          <div class="info-kv-row">
            <span class="info-kv-key">상태</span>
            <span class="info-kv-val">${info.HasPendingRestart ? '재시작 대기 중' : (info.HasUpdateAvailable ? '업데이트 가능 · 정상 가동' : '정상 가동 중')}</span>
          </div>
          ${info.LocalAddress ? `
            <div class="info-kv-row">
              <span class="info-kv-key">로컬 주소</span>
              <span class="info-kv-val">${escapeHtml(info.LocalAddress)}</span>
            </div>
          ` : ''}
        </div>
        ${info.CanSelfRestart !== false ? `
          <button type="button" class="danger-btn" id="admin-restart-server">
            <span class="mini-icon">${ICONS.power}</span>
            <span>Emby 서버 재시작</span>
          </button>
        ` : ''}
      </div>
    </div>

    <!-- 2. 라이브러리 스캔 -->
    <div class="settings-section">
      <h3 class="settings-section-title">라이브러리 스캔</h3>
      <div class="card-panel">
        <button type="button" class="primary-btn" id="admin-scan-all-libs">
          <span class="mini-icon">${ICONS.refresh}</span>
          <span>전체 라이브러리 스캔 실행</span>
        </button>
        ${libraries.length > 0 ? `
          <div class="admin-list">
            ${libraries.map((lib) => `
              <div class="admin-row">
                <div class="admin-row-info">
                  <strong>${escapeHtml(lib.Name)}</strong>
                  <span>${escapeHtml(lib.CollectionType || '라이브러리')}</span>
                </div>
                <button type="button" class="secondary-btn compact-btn" data-scan-lib="${escapeHtml(lib.Id)}" data-scan-lib-name="${escapeHtml(lib.Name)}">
                  스캔
                </button>
              </div>
            `).join('')}
          </div>
        ` : ''}
      </div>
    </div>

    <!-- 3. 활성 세션 모니터링 -->
    <div class="settings-section">
      <div class="section-header-inline">
        <h3 class="settings-section-title">활성 세션 모니터링</h3>
        <span class="section-count">재생 중 ${playingSessions.length} · 대기 ${idleCount}</span>
      </div>
      <div class="card-panel">
        ${playingSessions.length === 0 ? `
          <p class="empty-inline">현재 서버에서 재생 중인 세션이 없습니다.</p>
        ` : `
          <div class="admin-list">
            ${playingSessions.map((s) => {
              const np = s.NowPlayingItem;
              const ps = s.PlayState || {};
              const posSec = Math.floor((ps.PositionTicks || 0) / TICKS_PER_SECOND);
              const durSec = Math.floor((np.RunTimeTicks || 0) / TICKS_PER_SECOND);
              const pct = durSec > 0 ? Math.min(100, (posSec / durSec) * 100) : 0;
              const pm = playMethodLabel(s);
              const title = np.Type === 'Episode'
                ? `${np.SeriesName || ''} · ${formatEpisodeCode(np)} ${np.Name || ''}`.trim()
                : (np.Name || '재생 중');
              return `
                <div class="session-card">
                  <div class="session-top">
                    <div class="session-user">
                      <strong>${escapeHtml(s.UserName || '사용자')}</strong>
                      <span>${escapeHtml([s.DeviceName, s.Client].filter(Boolean).join(' · '))}</span>
                    </div>
                    <span class="status-badge ${pm.cls}">${pm.text}</span>
                  </div>
                  <div class="session-title">${escapeHtml(title)}</div>
                  <div class="session-meta">
                    <span>${ps.IsPaused ? '일시정지' : '재생 중'}</span>
                    <span>${formatTimestamp(posSec)}${durSec > 0 ? ` / ${formatTimestamp(durSec)}` : ''}</span>
                  </div>
                  ${durSec > 0 ? `<div class="progress-bar-flat"><div style="width:${pct.toFixed(1)}%"></div></div>` : ''}
                </div>
              `;
            }).join('')}
          </div>
        `}
      </div>
    </div>

    <!-- 4. 예약 작업 -->
    <div class="settings-section">
      <div class="section-header-inline">
        <h3 class="settings-section-title">예약 작업</h3>
        <span class="section-count">${tasks.length}개</span>
      </div>
      <div class="card-panel">
        ${tasks.length === 0 ? '<p class="empty-inline">표시할 예약 작업이 없습니다.</p>' : `
          <div class="admin-list">
            ${tasks.map((t) => {
              const st = taskStateLabel(t.State);
              const isRunning = t.State === 'Running';
              const pct = typeof t.CurrentProgressPercentage === 'number' ? Math.round(t.CurrentProgressPercentage) : 0;
              const lastRes = t.LastExecutionResult;
              const lastInfo = lastRes && lastRes.EndTimeUtc
                ? `최근: ${formatDateTime(lastRes.EndTimeUtc)} (${lastRes.Status === 'Completed' ? '성공' : lastRes.Status})`
                : (t.Description || '');
              return `
                <div class="task-card">
                  <div class="task-top">
                    <div class="admin-row-info">
                      <strong>${escapeHtml(t.Name)}</strong>
                      <span>${escapeHtml(lastInfo)}</span>
                    </div>
                    <div class="task-controls">
                      <span class="status-badge ${st.cls}">${isRunning ? `${pct}%` : st.text}</span>
                      ${isRunning
                        ? `<button type="button" class="danger-btn compact-btn" data-stop-task="${escapeHtml(t.Id)}" data-task-name="${escapeHtml(t.Name)}">중지</button>`
                        : `<button type="button" class="secondary-btn compact-btn" data-start-task="${escapeHtml(t.Id)}" data-task-name="${escapeHtml(t.Name)}">실행</button>`
                      }
                    </div>
                  </div>
                  ${isRunning ? `<div class="progress-bar-flat"><div style="width:${pct}%"></div></div>` : ''}
                </div>
              `;
            }).join('')}
          </div>
        `}
      </div>
    </div>
  `;
}

export function renderSettingsView(appEl, view, {
  bindCommonActions,
  onReloadHome,
  onLoggedOut,
  onAccountSwitched
}) {
  const admin = isAdmin();
  if (!admin && view.activeTab === 'admin') {
    view.activeTab = 'user';
  }

  const tabsHtml = admin ? `
    <div class="settings-tabs-wrap">
      <div class="segmented-tabs">
        <button type="button" class="seg-tab ${view.activeTab === 'user' ? 'active' : ''}" data-settings-tab="user">사용자 설정</button>
        <button type="button" class="seg-tab ${view.activeTab === 'admin' ? 'active' : ''}" data-settings-tab="admin">서버 관리</button>
      </div>
    </div>
  ` : '';

  const contentHtml = view.activeTab === 'admin'
    ? renderAdminDashboardTab(view)
    : renderUserSettingsTab();

  appEl.innerHTML = `
    ${renderTopBar({ title: 'Emby 설정', showBack: true, showSearch: false, showRefresh: view.activeTab === 'admin', showSettings: false })}
    <main class="settings-page">
      ${tabsHtml}
      ${contentHtml}
    </main>
  `;

  bindCommonActions();

  // 탭 전환
  appEl.querySelectorAll('[data-settings-tab]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const next = btn.dataset.settingsTab;
      if (next === view.activeTab) return;
      view.activeTab = next;
      triggerRender();
      if (next === 'admin') {
        loadAdminDashboardData(view, false);
      }
    });
  });

  if (view.activeTab === 'user') {
    // 서버 URL 저장
    document.getElementById('settings-save-url').addEventListener('click', async () => {
      const nextUrl = normalizeBaseUrl(document.getElementById('settings-server-url').value);
      if (!nextUrl) {
        showToast('올바른 서버 URL을 입력해 주세요.', 'error');
        return;
      }
      state.customServerUrl = nextUrl;
      api.configure({ baseUrl: nextUrl });
      await window.UnraidHub.storageSet(STORAGE_KEYS.CUSTOM_SERVER_URL, nextUrl);
      showToast('Emby 서버 주소가 변경되었습니다.', 'success');
      triggerRender();
      onReloadHome();
    });

    // 자동 주소 복원
    document.getElementById('settings-reset-url').addEventListener('click', async () => {
      state.customServerUrl = '';
      await window.UnraidHub.storageRemove(STORAGE_KEYS.CUSTOM_SERVER_URL);
      const autoUrl = resolveActiveBaseUrl('', state.context);
      api.configure({ baseUrl: autoUrl });
      showToast('자동 감지된 도커 주소로 복원되었습니다.', 'success');
      triggerRender();
      onReloadHome();
    });

    // 계정 전환
    document.getElementById('settings-switch-account').addEventListener('click', () => {
      openAccountSwitcherSheet({
        onSwitched: () => onAccountSwitched(),
        onNewLogin: async () => {
          await clearActiveSession();
          api.configure({ accessToken: '', userId: '' });
          onLoggedOut();
        }
      });
    });

    // 로그아웃
    document.getElementById('settings-logout').addEventListener('click', () => {
      openConfirmModal({
        title: '로그아웃',
        message: `${state.userName || '현재'} 계정에서 로그아웃하시겠습니까?`,
        confirmLabel: '로그아웃',
        danger: true,
        onConfirm: async () => {
          await clearActiveSession();
          api.configure({ accessToken: '', userId: '' });
          showToast('로그아웃되었습니다.');
          onLoggedOut();
        }
      });
    });

    // 다음 화 자동 재생 토글
    document.getElementById('pref-auto-play-next').addEventListener('change', async (e) => {
      await savePref('autoPlayNext', Boolean(e.target.checked));
      showToast(e.target.checked ? '다음 화 자동 재생이 켜졌습니다.' : '다음 화 자동 재생이 꺼졌습니다.');
    });

    // 선호 오디오 언어
    document.getElementById('pref-audio-lang').addEventListener('change', async (e) => {
      await savePref('preferredAudioLang', e.target.value);
      showToast('기본 선호 오디오 언어가 저장되었습니다.', 'success');
    });

    // 선호 자막 언어
    document.getElementById('pref-sub-lang').addEventListener('change', async (e) => {
      await savePref('preferredSubLang', e.target.value);
      showToast('기본 선호 자막 언어가 저장되었습니다.', 'success');
    });

    // 라이브러리 그리드 열 수
    appEl.querySelectorAll('[data-grid-cols]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const cols = parseInt(btn.dataset.gridCols, 10);
        await savePref('gridCols', cols);
        triggerRender();
        showToast(`라이브러리 그리드를 ${cols}열로 설정했습니다.`, 'success');
      });
    });
  } else {
    // 서버 관리 탭 이벤트 바인딩
    const refreshBtn = document.getElementById('admin-refresh-all');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', () => loadAdminDashboardData(view, false));
    }

    const scanAllBtn = document.getElementById('admin-scan-all-libs');
    if (scanAllBtn) {
      scanAllBtn.addEventListener('click', async () => {
        scanAllBtn.disabled = true;
        try {
          await api.refreshAllLibraries();
          showToast('전체 라이브러리 스캔을 시작했습니다.', 'success');
          setTimeout(() => loadAdminDashboardData(view, true), 1200);
        } catch (err) {
          reportPluginError('ScanAllLibraries', '전체 라이브러리 스캔 요청에 실패했습니다.', err);
        } finally {
          scanAllBtn.disabled = false;
        }
      });
    }

    appEl.querySelectorAll('[data-scan-lib]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const libId = btn.dataset.scanLib;
        const libName = btn.dataset.scanLibName;
        btn.disabled = true;
        try {
          await api.refreshLibrary(libId);
          showToast(`'${libName}' 라이브러리 스캔을 시작했습니다.`, 'success');
        } catch (err) {
          reportPluginError('ScanLibrary', `'${libName}' 스캔 요청에 실패했습니다.`, err);
        } finally {
          btn.disabled = false;
        }
      });
    });

    appEl.querySelectorAll('[data-start-task]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const taskId = btn.dataset.startTask;
        const taskName = btn.dataset.taskName;
        btn.disabled = true;
        try {
          await api.startScheduledTask(taskId);
          showToast(`'${taskName}' 작업을 실행했습니다.`, 'success');
          setTimeout(() => loadAdminDashboardData(view, true), 900);
        } catch (err) {
          btn.disabled = false;
          reportPluginError('StartTask', '예약 작업 실행에 실패했습니다.', err);
        }
      });
    });

    appEl.querySelectorAll('[data-stop-task]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const taskId = btn.dataset.stopTask;
        const taskName = btn.dataset.taskName;
        btn.disabled = true;
        try {
          await api.stopScheduledTask(taskId);
          showToast(`'${taskName}' 작업 중지를 요청했습니다.`);
          setTimeout(() => loadAdminDashboardData(view, true), 900);
        } catch (err) {
          btn.disabled = false;
          reportPluginError('StopTask', '예약 작업 중지에 실패했습니다.', err);
        }
      });
    });

    const restartBtn = document.getElementById('admin-restart-server');
    if (restartBtn) {
      restartBtn.addEventListener('click', () => {
        openConfirmModal({
          title: 'Emby 서버 재시작',
          message: 'Emby 서버를 지금 재시작하시겠습니까? 현재 재생 중인 모든 기기의 연결이 일시적으로 끊깁니다.',
          confirmLabel: '서버 재시작',
          danger: true,
          onConfirm: async () => {
            try {
              await api.restartServer();
              showToast('Emby 서버 재시작을 요청했습니다. 잠시 후 다시 연결해 주세요.', 'success', 4500);
            } catch (err) {
              reportPluginError('RestartServer', 'Emby 서버 재시작 요청에 실패했습니다.', err);
              throw err;
            }
          }
        });
      });
    }
  }
}
