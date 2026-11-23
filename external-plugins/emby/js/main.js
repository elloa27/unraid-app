/**
 * Emby 전용 UI v2.0.0 메인 엔트리포인트 (ES Module)
 * - 앱 부트스트랩, 뷰 라우터, 히스토리 뒤로가기, 재생 종료 후 동기화 연동
 */
import { api } from './api.js';
import {
  state,
  setRenderHandler,
  triggerRender,
  resolveActiveBaseUrl,
  currentView,
  pushView,
  popViewImmediate,
  navigateBack,
  loadSavedSettings
} from './store.js';
import { escapeHtml } from './utils.js';
import { reportPluginError } from './components/toast.js';
import { hasOpenOverlay, closeTopOverlay, closeAllOverlays } from './components/modal.js';
import { cancelNextEpisodeCountdown } from './components/countdown.js';
import {
  playEmbyItem,
  initPlaybackEventListeners,
  setAfterPlaybackRefreshHandler
} from './playback.js';

import { renderLoginView } from './views/login.js';
import { loadHomeData, renderHomeView } from './views/home.js';
import { createLibraryViewState, loadLibraryItems, renderLibraryView } from './views/library.js';
import { renderSearchView } from './views/search.js';
import { loadSeriesData, loadSeriesEpisodes, renderSeriesView } from './views/series.js';
import { createDetailViewState, loadDetailData, renderDetailView } from './views/detail.js';
import { createPersonViewState, loadPersonData, renderPersonView } from './views/person.js';
import { createPlaylistViewState, loadPlaylistData, renderPlaylistView } from './views/playlist.js';
import { createSettingsViewState, loadAdminDashboardData, renderSettingsView } from './views/settings.js';
import { openItemMoreMenuSheet } from './views/metadata-modals.js';

const appEl = document.getElementById('app');

// =========================================================================
// 네비게이션 액션
// =========================================================================

async function openHomeFresh() {
  state.stack = [];
  pushView({ type: 'home' });
  await loadHomeData(false);
}

function openLoginScreen() {
  closeAllOverlays();
  cancelNextEpisodeCountdown();
  state.stack = [];
  pushView({
    type: 'login',
    serverUrl: api.baseUrl || state.context.activeBaseUrl || '',
    username: state.userName || '',
    error: '',
    submitting: false
  });
}

async function openLibrary(library) {
  const view = createLibraryViewState(library);
  pushView(view);
  await loadLibraryItems(view, { reset: true });
}

function openSearch() {
  pushView({
    type: 'search',
    query: '',
    loading: false,
    error: '',
    results: []
  });
}

async function openSeries(seriesSummary) {
  const view = {
    type: 'series',
    seriesId: seriesSummary.Id,
    series: seriesSummary,
    seasons: [],
    selectedSeasonId: '',
    episodes: [],
    similar: [],
    loading: true,
    episodesLoading: false,
    error: ''
  };
  pushView(view);
  await loadSeriesData(view, { silent: false });
}

async function openDetail(itemId, previewItem = null, replace = false) {
  const view = createDetailViewState(itemId, previewItem);
  pushView(view, replace);
  await loadDetailData(view, false);
}

async function openPerson(person) {
  if (!person || !person.Id) return;
  const view = createPersonViewState(person);
  pushView(view);
  await loadPersonData(view, { reset: true });
}

async function openPlaylist(playlist) {
  if (!playlist || !playlist.Id) return;
  const view = createPlaylistViewState(playlist);
  pushView(view);
  await loadPlaylistData(view, false);
}

function openSettings(initialTab = 'user') {
  const top = currentView();
  if (top && top.type === 'settings') return;
  const view = createSettingsViewState(initialTab);
  pushView(view);
  if (view.activeTab === 'admin') {
    loadAdminDashboardData(view, false);
  }
}

async function openItem(item) {
  if (!item || !item.Id) return;
  if (item.Type === 'Series') {
    await openSeries(item);
    return;
  }
  if (item.Type === 'Person') {
    await openPerson(item);
    return;
  }
  if (item.Type === 'Playlist') {
    await openPlaylist(item);
    return;
  }
  if (item.Type === 'Folder' || item.Type === 'CollectionFolder' || item.Type === 'BoxSet') {
    await openLibrary({
      Id: item.Id,
      Name: item.Name,
      Type: item.Type,
      CollectionType: item.CollectionType || ''
    });
    return;
  }
  await openDetail(item.Id, item);
}

function handleItemDeleted() {
  if (state.stack.length > 1) {
    popViewImmediate();
    const top = currentView();
    if (top && top.type === 'library') loadLibraryItems(top, { reset: true });
    else if (top && top.type === 'series') loadSeriesEpisodes(top, top.selectedSeasonId, true);
    else if (top && top.type === 'home') loadHomeData(true);
  } else {
    openHomeFresh();
  }
}

// =========================================================================
// 공통 버튼 바인딩 & 뒤로가기 처리
// =========================================================================

function bindCommonActions() {
  const backBtn = appEl.querySelector('[data-action="back"]');
  if (backBtn) backBtn.addEventListener('click', navigateBack);

  const searchBtn = appEl.querySelector('[data-action="open-search"]');
  if (searchBtn) searchBtn.addEventListener('click', openSearch);

  const settingsBtn = appEl.querySelector('[data-action="open-settings"]');
  if (settingsBtn) settingsBtn.addEventListener('click', () => openSettings('user'));

  appEl.querySelectorAll('[data-action="refresh"]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const top = currentView();
      if (!top) return;
      if (top.type === 'home') loadHomeData(false);
      else if (top.type === 'library') loadLibraryItems(top, { reset: true });
      else if (top.type === 'series') loadSeriesData(top, { silent: false });
      else if (top.type === 'detail') loadDetailData(top, false);
      else if (top.type === 'person') loadPersonData(top, { reset: true });
      else if (top.type === 'playlist') loadPlaylistData(top, false);
      else if (top.type === 'settings' && top.activeTab === 'admin') loadAdminDashboardData(top, false);
    });
  });

  const webviewBtn = appEl.querySelector('[data-action="open-webview"]');
  if (webviewBtn) {
    webviewBtn.addEventListener('click', () => {
      if (window.UnraidHub && window.UnraidHub.openWebView) window.UnraidHub.openWebView();
    });
  }

  const closeBtn = appEl.querySelector('[data-action="close-plugin"]');
  if (closeBtn) {
    closeBtn.addEventListener('click', () => {
      if (window.UnraidHub && window.UnraidHub.close) window.UnraidHub.close();
    });
  }
}

window.addEventListener('popstate', () => {
  cancelNextEpisodeCountdown();
  if (hasOpenOverlay()) {
    closeTopOverlay();
    // 오버레이만 닫고 현재 화면 유지
    try {
      const top = currentView();
      if (top) history.pushState({ depth: state.stack.length }, '', `#${top.type}`);
    } catch (_) {}
    return;
  }
  popViewImmediate();
});

// =========================================================================
// 재생 종료/중단 후 현재 화면 + 홈 이어보기/다음 화 즉시 갱신
// =========================================================================

setAfterPlaybackRefreshHandler(async () => {
  try {
    const top = currentView();
    if (!top) return;
    if (top.type === 'home') {
      await loadHomeData(true);
    } else if (top.type === 'detail' && top.itemId) {
      await loadDetailData(top, true);
      loadHomeData(true).catch(() => {});
    } else if (top.type === 'series' && top.seriesId) {
      await loadSeriesEpisodes(top, top.selectedSeasonId, true);
      loadHomeData(true).catch(() => {});
    } else if (top.type === 'playlist' && top.playlistId) {
      await loadPlaylistData(top, true);
      loadHomeData(true).catch(() => {});
    } else {
      loadHomeData(true).catch(() => {});
    }
  } catch (_) {}
});

// =========================================================================
// 메인 렌더 라우터
// =========================================================================

function render() {
  const view = currentView();
  if (!view) return;

  switch (view.type) {
    case 'login':
      renderLoginView(appEl, view, {
        onLoginSuccess: openHomeFresh,
        bindCommonActions
      });
      break;
    case 'home':
      renderHomeView(appEl, {
        bindCommonActions,
        onOpenLibrary: openLibrary,
        onOpenItem: openItem,
        onPlayItem: playEmbyItem
      });
      break;
    case 'library':
      renderLibraryView(appEl, view, {
        bindCommonActions,
        onOpenItem: openItem,
        onPlayItem: playEmbyItem,
        onOpenMoreMenu: (libItem) => {
          openItemMoreMenuSheet(libItem, {
            onChanged: () => {
              loadLibraryItems(view, { reset: true });
              loadHomeData(true).catch(() => {});
            },
            onDeleted: () => {
              loadHomeData(true).catch(() => {});
              handleItemDeleted();
            }
          });
        }
      });
      break;
    case 'search':
      renderSearchView(appEl, view, {
        bindCommonActions,
        onOpenItem: openItem,
        onPlayItem: playEmbyItem
      });
      break;
    case 'series':
      renderSeriesView(appEl, view, {
        bindCommonActions,
        onOpenDetail: openDetail,
        onOpenItem: openItem,
        onOpenPerson: openPerson,
        onPlayItem: playEmbyItem,
        onRefreshHome: () => loadHomeData(true).catch(() => {}),
        onItemDeleted: handleItemDeleted
      });
      break;
    case 'detail':
      renderDetailView(appEl, view, {
        bindCommonActions,
        onPlayItem: playEmbyItem,
        onOpenDetail: openDetail,
        onOpenSeries: openSeries,
        onOpenPerson: openPerson,
        onOpenItem: openItem,
        onRefreshHome: () => loadHomeData(true).catch(() => {}),
        onItemDeleted: handleItemDeleted
      });
      break;
    case 'person':
      renderPersonView(appEl, view, {
        bindCommonActions,
        onOpenItem: openItem,
        onPlayItem: playEmbyItem
      });
      break;
    case 'playlist':
      renderPlaylistView(appEl, view, {
        bindCommonActions,
        onOpenItem: openItem,
        onPlayItem: playEmbyItem,
        onRefreshHome: () => loadHomeData(true).catch(() => {}),
        onDeleted: handleItemDeleted
      });
      break;
    case 'settings':
      renderSettingsView(appEl, view, {
        bindCommonActions,
        onReloadHome: () => loadHomeData(false),
        onLoggedOut: openLoginScreen,
        onAccountSwitched: openHomeFresh
      });
      break;
    default:
      break;
  }
}

setRenderHandler(render);

// =========================================================================
// 부트스트랩 초기화
// =========================================================================

async function bootstrap() {
  try {
    if (!window.UnraidHub) {
      throw new Error('window.UnraidHub 네이티브 브릿지를 찾을 수 없습니다.');
    }

    initPlaybackEventListeners();

    const ctx = await window.UnraidHub.getContext();
    state.context = { ...state.context, ...(ctx || {}) };

    const saved = await loadSavedSettings();
    const baseUrl = resolveActiveBaseUrl(saved.customServerUrl, state.context);

    api.configure({
      baseUrl,
      accessToken: saved.accessToken,
      userId: saved.userId,
      deviceId: saved.deviceId
    });

    if (saved.accessToken && saved.userId && baseUrl) {
      state.isAuthenticated = true;
      // 사용자 권한(Policy.IsAdministrator 등) 비동기 확보
      api.getCurrentUser()
        .then((userObj) => {
          state.user = userObj;
          if (userObj && userObj.Name) state.userName = userObj.Name;
          const top = currentView();
          if (top && top.type === 'settings') triggerRender();
        })
        .catch(() => {});

      await openHomeFresh();
    } else {
      state.isAuthenticated = false;
      openLoginScreen();
    }
  } catch (err) {
    reportPluginError('Bootstrap', err.message || 'Emby 플러그인 초기화에 실패했습니다.', err);
    appEl.innerHTML = `
      <div class="center-state">
        <p class="state-title">플러그인 초기화 오류</p>
        <p class="state-desc">${escapeHtml(err.message || '알 수 없는 오류가 발생했습니다.')}</p>
        <button class="secondary-btn" data-action="open-webview">일반 웹뷰로 열기</button>
        <button class="ghost-link" data-action="close-plugin">런처로 복귀</button>
      </div>
    `;
    bindCommonActions();
  }
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', bootstrap);
} else {
  bootstrap();
}
