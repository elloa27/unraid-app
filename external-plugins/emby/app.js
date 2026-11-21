/**
 * Emby 전용 UI 외부 플러그인 메인 애플리케이션 (Vanilla ES6+)
 * - window.UnraidHub 네이티브 브릿지 기반 (httpRequest, storage*, playVideo, getContext, logError)
 * - 모바일 네이티브 느낌의 홈(이어보기/라이브러리/최근 추가), 라이브러리 정렬 그리드,
 *   실시간 검색, 시리즈→시즌→에피소드, 상세 화면, libmpv Direct Play 및 이어보기 동기화를 제공합니다.
 */
(function () {
  'use strict';

  const STORAGE_KEYS = {
    ACCESS_TOKEN: 'emby_access_token',
    USER_ID: 'emby_user_id',
    USER_NAME: 'emby_user_name',
    CUSTOM_SERVER_URL: 'emby_custom_server_url',
    DEVICE_ID: 'emby_device_id',
    LIBRARY_SORT: 'emby_library_sort'
  };

  const SORT_OPTIONS = [
    { key: 'name_asc', label: '이름순' },
    { key: 'name_desc', label: '이름 역순' },
    { key: 'date_added', label: '최신 추가순' },
    { key: 'release_desc', label: '출시연도순' },
    { key: 'rating_desc', label: '평점순' }
  ];

  const ICONS = {
    back: '<svg viewBox="0 0 24 24"><path d="M19 12H5"/><path d="M12 19l-7-7 7-7"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.35-4.35"/></svg>',
    refresh: '<svg viewBox="0 0 24 24"><path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/></svg>',
    settings: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h.01a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h.01a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v.01a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',
    play: '<svg viewBox="0 0 24 24" style="fill:currentColor;stroke:none;"><polygon points="6,4 20,12 6,20"/></svg>',
    close: '<svg viewBox="0 0 24 24"><path d="M18 6L6 18"/><path d="M6 6l12 12"/></svg>'
  };

  const api = new window.EmbyApiClient();
  const appEl = document.getElementById('app');
  const modalRoot = document.getElementById('modal-root');
  const toastRoot = document.getElementById('toast-root');

  const state = {
    context: {
      serviceName: 'Emby',
      serviceUrl: '',
      tailscaleUrl: '',
      activeBaseUrl: '',
      isTailscaleActive: false
    },
    customServerUrl: '',
    userName: '',
    isAuthenticated: false,
    librarySort: 'name_asc',
    stack: [],
    activePlaybackSession: null,
    homeData: {
      loading: false,
      error: '',
      resume: [],
      libraries: [],
      latest: []
    },
    searchTimer: null
  };

  // =========================================================================
  // 유틸리티 & 에러/토스트 처리
  // =========================================================================

  function escapeHtml(str) {
    return String(str == null ? '' : str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function formatRuntime(ticks) {
    const totalMin = Math.round(Number(ticks || 0) / window.EmbyUtils.TICKS_PER_SECOND / 60);
    if (!totalMin || totalMin <= 0) return '';
    const h = Math.floor(totalMin / 60);
    const m = totalMin % 60;
    return h > 0 ? `${h}시간 ${m}분` : `${m}분`;
  }

  function formatTimestamp(seconds) {
    const sec = Math.max(0, Math.floor(Number(seconds || 0)));
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    const mm = String(m).padStart(2, '0');
    const ss = String(s).padStart(2, '0');
    return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
  }

  function getProgressPercent(item) {
    const ud = item && item.UserData;
    if (!ud) return 0;
    if (typeof ud.PlayedPercentage === 'number' && ud.PlayedPercentage > 0) {
      return Math.min(100, Math.max(0, ud.PlayedPercentage));
    }
    if (ud.PlaybackPositionTicks && item.RunTimeTicks) {
      return Math.min(100, Math.max(0, (ud.PlaybackPositionTicks / item.RunTimeTicks) * 100));
    }
    return 0;
  }

  function getCollectionTypeLabel(type) {
    const map = {
      movies: '영화',
      tvshows: '시리즈',
      music: '음악',
      homevideos: '비디오',
      boxsets: '컬렉션',
      playlists: '재생목록',
      mixed: '통합'
    };
    return map[String(type || '').toLowerCase()] || '라이브러리';
  }

  function showToast(message, type = 'info') {
    const el = document.createElement('div');
    el.className = `toast ${type}`;
    el.textContent = message;
    toastRoot.appendChild(el);
    setTimeout(() => {
      el.style.opacity = '0';
      el.style.transition = 'opacity 0.2s ease';
      setTimeout(() => el.remove(), 220);
    }, 3200);
  }

  function reportPluginError(tag, userMessage, error) {
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

  function resolveActiveBaseUrl(customUrl, ctx) {
    const cleanCustom = window.EmbyUtils.normalizeBaseUrl(customUrl);
    const cleanService = window.EmbyUtils.normalizeBaseUrl(ctx.serviceUrl);
    const cleanActive = window.EmbyUtils.normalizeBaseUrl(ctx.activeBaseUrl);

    if (cleanCustom) {
      // 사용자가 기본 LAN URL을 그대로 둔 상태에서 Tailscale이 활성화된 경우 자동으로 Tailscale 주소 사용
      if (ctx.isTailscaleActive && cleanService && cleanCustom === cleanService && cleanActive) {
        return cleanActive;
      }
      return cleanCustom;
    }
    return cleanActive || cleanService;
  }

  function currentView() {
    return state.stack[state.stack.length - 1] || null;
  }

  function pushView(view, replace = false) {
    if (replace && state.stack.length > 0) {
      state.stack[state.stack.length - 1] = view;
      try {
        history.replaceState({ depth: state.stack.length }, '', `#${view.type}`);
      } catch (_) {}
    } else {
      state.stack.push(view);
      try {
        if (state.stack.length === 1) {
          history.replaceState({ depth: 1 }, '', `#${view.type}`);
        } else {
          history.pushState({ depth: state.stack.length }, '', `#${view.type}`);
        }
      } catch (_) {}
    }
    render();
    window.scrollTo(0, 0);
  }

  function navigateBack() {
    if (state.stack.length > 1) {
      history.back();
    } else if (window.UnraidHub && typeof window.UnraidHub.close === 'function') {
      window.UnraidHub.close();
    }
  }

  window.addEventListener('popstate', () => {
    if (!modalRoot.classList.contains('hidden')) {
      closeSettingsModal();
    }
    if (state.stack.length > 1) {
      state.stack.pop();
      render();
    }
  });

  // =========================================================================
  // 내장 libmpv 재생 & Emby 이어보기 동기화
  // =========================================================================

  window.addEventListener('unraidhub:playerProgress', (event) => {
    const detail = (event && event.detail) || {};
    const session = state.activePlaybackSession;
    if (!session) return;
    api.reportPlaybackProgress(session, detail.positionSec || 0, false).catch((err) => {
      if (window.UnraidHub && window.UnraidHub.logError) {
        window.UnraidHub.logError('PlaybackSync', '재생 진행률 동기화 실패', err.detail || err.message);
      }
    });
  });

  window.addEventListener('unraidhub:playerClosed', async (event) => {
    const detail = (event && event.detail) || {};
    const session = state.activePlaybackSession;
    state.activePlaybackSession = null;

    if (session) {
      try {
        await api.reportPlaybackStopped(session, detail.positionSec || 0);
      } catch (err) {
        if (window.UnraidHub && window.UnraidHub.logError) {
          window.UnraidHub.logError('PlaybackSync', '재생 종료 동기화 실패', err.detail || err.message);
        }
      }
    }

    // 방금 시청한 진행률이 화면에 바로 반영되도록 현재 뷰와 홈 데이터 조용히 갱신
    refreshCurrentViewAfterPlayback();
  });

  async function refreshCurrentViewAfterPlayback() {
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
      }
    } catch (_) {}
  }

  async function playEmbyItem(item, resume = true) {
    try {
      let fullItem = item;
      const hasMediaSources = Array.isArray(item.MediaSources) && item.MediaSources.length > 0;
      if (!hasMediaSources) {
        showToast('스트림 정보를 불러오는 중...');
        fullItem = await api.getItemDetail(item.Id);
      }

      const { session, playVideoOptions } = api.buildDirectPlayRequest(fullItem, resume);
      state.activePlaybackSession = session;

      // Emby 서버에 재생 시작 보고 (비동기, 실패해도 재생은 계속 진행)
      api.reportPlaybackStarted(session, playVideoOptions.startPositionSec).catch((err) => {
        if (window.UnraidHub && window.UnraidHub.logError) {
          window.UnraidHub.logError('PlaybackStart', 'Emby 재생 시작 보고 실패', err.detail || err.message);
        }
      });

      await window.UnraidHub.playVideo(playVideoOptions);
    } catch (err) {
      state.activePlaybackSession = null;
      reportPluginError('PlayVideo', err.message || '동영상 재생을 시작할 수 없습니다.', err);
    }
  }

  // =========================================================================
  // 데이터 로딩 함수
  // =========================================================================

  async function loadHomeData(silent = false) {
    if (!silent) {
      state.homeData.loading = true;
      state.homeData.error = '';
      render();
    }

    try {
      const [resume, libraries, latest] = await Promise.all([
        api.getResumeItems(18),
        api.getLibraries(),
        api.getLatestItems('', 24)
      ]);

      state.homeData = {
        loading: false,
        error: '',
        resume,
        libraries,
        latest
      };
    } catch (err) {
      state.homeData.loading = false;
      state.homeData.error = err.message || 'Emby 홈 데이터를 불러오지 못했습니다.';
      reportPluginError('HomeLoad', state.homeData.error, err);
      if (err.isAuthError) {
        showToast('인증이 만료되었습니다. 다시 로그인해 주세요.', 'error');
      }
    }
    render();
  }

  async function openLibrary(library) {
    const view = {
      type: 'library',
      library,
      sortKey: state.librarySort || 'name_asc',
      loading: true,
      error: '',
      items: []
    };
    pushView(view);
    await loadLibraryItems(view);
  }

  async function loadLibraryItems(view) {
    view.loading = true;
    view.error = '';
    render();
    try {
      view.items = await api.getLibraryItems(view.library, view.sortKey);
      view.loading = false;
    } catch (err) {
      view.loading = false;
      view.error = err.message || '라이브러리 항목을 불러오지 못했습니다.';
      reportPluginError('LibraryLoad', view.error, err);
    }
    render();
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

  async function runSearch(view, query) {
    const trimmed = String(query || '').trim();
    view.query = query;
    if (!trimmed) {
      view.loading = false;
      view.error = '';
      view.results = [];
      renderSearchResultsOnly(view);
      return;
    }
    view.loading = true;
    view.error = '';
    renderSearchResultsOnly(view);
    try {
      const items = await api.searchItems(trimmed);
      // 사용자가 입력을 더 진행한 경우 이전 결과 무시
      if (view.query.trim() !== trimmed) return;
      view.results = items;
      view.loading = false;
    } catch (err) {
      if (view.query.trim() !== trimmed) return;
      view.loading = false;
      view.error = err.message || '검색 중 오류가 발생했습니다.';
      reportPluginError('Search', view.error, err);
    }
    renderSearchResultsOnly(view);
  }

  async function openItem(item) {
    if (!item || !item.Id) return;
    if (item.Type === 'Series') {
      await openSeries(item);
      return;
    }
    if (item.Type === 'Folder' || item.Type === 'CollectionFolder' || item.Type === 'BoxSet') {
      await openLibrary({ Id: item.Id, Name: item.Name, CollectionType: item.CollectionType || '' });
      return;
    }
    await openDetail(item.Id, item);
  }

  async function openSeries(seriesSummary) {
    const view = {
      type: 'series',
      seriesId: seriesSummary.Id,
      series: seriesSummary,
      seasons: [],
      selectedSeasonId: '',
      episodes: [],
      loading: true,
      episodesLoading: false,
      error: ''
    };
    pushView(view);

    try {
      const [detail, seasons] = await Promise.all([
        api.getItemDetail(seriesSummary.Id),
        api.getSeasons(seriesSummary.Id)
      ]);
      view.series = detail || seriesSummary;
      view.seasons = seasons;
      view.loading = false;

      if (seasons.length > 0) {
        // 시청하지 않은 에피소드가 남아 있는 첫 정규 시즌 우선 선택
        const preferred = seasons.find(
          (s) => (s.IndexNumber == null || s.IndexNumber > 0) && !(s.UserData && s.UserData.Played)
        ) || seasons.find((s) => s.IndexNumber == null || s.IndexNumber > 0) || seasons[0];
        view.selectedSeasonId = preferred.Id;
        await loadSeriesEpisodes(view, preferred.Id);
      } else {
        await loadSeriesEpisodes(view, '');
      }
    } catch (err) {
      view.loading = false;
      view.error = err.message || '시리즈 정보를 불러오지 못했습니다.';
      reportPluginError('SeriesLoad', view.error, err);
      render();
    }
  }

  async function loadSeriesEpisodes(view, seasonId, silent = false) {
    view.selectedSeasonId = seasonId || '';
    if (!silent) {
      view.episodesLoading = true;
      render();
    }
    try {
      view.episodes = await api.getEpisodes(view.seriesId, view.selectedSeasonId);
      view.episodesLoading = false;
    } catch (err) {
      view.episodesLoading = false;
      view.error = err.message || '에피소드 목록을 불러오지 못했습니다.';
      reportPluginError('EpisodesLoad', view.error, err);
    }
    render();
  }

  async function openDetail(itemId, previewItem = null, replace = false) {
    const view = {
      type: 'detail',
      itemId,
      item: previewItem,
      siblingEpisodes: [],
      loading: true,
      error: ''
    };
    pushView(view, replace);
    await loadDetailData(view, false);
  }

  async function loadDetailData(view, silent = false) {
    if (!silent) {
      view.loading = true;
      view.error = '';
      render();
    }
    try {
      const detail = await api.getItemDetail(view.itemId);
      view.item = detail;
      view.loading = false;

      // 에피소드 상세 화면에서 이전화/다음화 이동을 지원하기 위해 같은 시즌 목록 조회
      if (detail && detail.Type === 'Episode' && detail.SeriesId) {
        try {
          view.siblingEpisodes = await api.getEpisodes(detail.SeriesId, detail.SeasonId || '');
        } catch (_) {
          view.siblingEpisodes = [];
        }
      }
    } catch (err) {
      view.loading = false;
      view.error = err.message || '상세 정보를 불러오지 못했습니다.';
      reportPluginError('DetailLoad', view.error, err);
    }
    render();
  }

  // =========================================================================
  // 렌더링 helpers
  // =========================================================================

  function renderFallbackImage(url, alt, initialText, extraClass = '') {
    const initial = escapeHtml((initialText || alt || '?').trim().charAt(0).toUpperCase() || '?');
    const safeAlt = escapeHtml(alt || '');
    return `
      <div class="img-fallback">
        <span class="img-fallback-initial">${initial}</span>
        <span class="img-fallback-label">${safeAlt}</span>
      </div>
      ${url ? `<img src="${escapeHtml(url)}" alt="${safeAlt}" class="${extraClass}" loading="lazy" data-fallback-img="1" />` : ''}
    `;
  }

  function bindFallbackImages(root = appEl) {
    root.querySelectorAll('img[data-fallback-img]').forEach((img) => {
      img.addEventListener('error', () => {
        img.style.display = 'none';
      }, { once: true });
    });
  }

  function renderTopBar({ title, showBack = false, showSearch = true, showRefresh = false }) {
    const routeLabel = state.context.isTailscaleActive ? 'Tailscale' : '직결';
    const routeClass = state.context.isTailscaleActive ? 'route-badge tailscale' : 'route-badge';

    return `
      <header class="top-bar">
        <div class="top-bar-inner">
          ${showBack ? `<button class="icon-btn" data-action="back" aria-label="뒤로가기">${ICONS.back}</button>` : ''}
          <div class="top-bar-title-group">
            ${!showBack ? '<span class="brand-dot"></span>' : ''}
            <h1 class="top-bar-title">${escapeHtml(title)}</h1>
            ${!showBack ? `<span class="${routeClass}">${routeLabel}</span>` : ''}
          </div>
          <div class="top-bar-actions">
            ${showSearch ? `<button class="icon-btn" data-action="open-search" aria-label="검색">${ICONS.search}</button>` : ''}
            ${showRefresh ? `<button class="icon-btn" data-action="refresh" aria-label="새로고침">${ICONS.refresh}</button>` : ''}
            <button class="icon-btn" data-action="open-settings" aria-label="설정">${ICONS.settings}</button>
          </div>
        </div>
      </header>
    `;
  }

  function renderPosterCard(item, gridMode = false) {
    const posterUrl = api.getPosterUrl(item, 400);
    const isPlayed = Boolean(item.UserData && item.UserData.Played);
    const unplayedCount = item.UserData && item.UserData.UnplayedItemCount;
    const subText = item.Type === 'Episode'
      ? [item.SeriesName, window.EmbyUtils.formatEpisodeCode(item)].filter(Boolean).join(' · ')
      : [item.ProductionYear, item.Type === 'Series' ? '시리즈' : (item.Type === 'Movie' ? '영화' : '')].filter(Boolean).join(' · ');

    let badgeHtml = '';
    if (isPlayed) {
      badgeHtml = '<span class="poster-badge-top played">✓</span>';
    } else if (item.Type === 'Series' && unplayedCount > 0) {
      badgeHtml = `<span class="poster-badge-top">${unplayedCount}</span>`;
    }

    return `
      <button class="poster-card ${gridMode ? 'grid-card' : ''}" data-open-item-id="${escapeHtml(item.Id)}">
        <div class="poster-2-3">
          ${renderFallbackImage(posterUrl, item.Name, item.Name)}
          ${badgeHtml}
        </div>
        <div class="poster-meta">
          <p class="card-title">${escapeHtml(item.Name || '제목 없음')}</p>
          <p class="card-sub">${subText ? escapeHtml(subText) : '&nbsp;'}</p>
        </div>
      </button>
    `;
  }

  // =========================================================================
  // 화면별 렌더러
  // =========================================================================

  function renderLoginView(view) {
    const defaultUrl = view.serverUrl || api.baseUrl || state.context.activeBaseUrl || '';
    const routeText = state.context.isTailscaleActive
      ? '내장 Tailscale(tsnet) 프록시 활성'
      : '홈 네트워크 직접 연결';

    appEl.innerHTML = `
      <main class="login-page">
        <div class="login-brand">
          <div class="login-logo">
            <svg viewBox="0 0 24 24"><polygon points="7,4 20,12 7,20"/></svg>
          </div>
          <h1 class="login-title">Emby 로그인</h1>
          <p class="login-subtitle">${escapeHtml(state.context.serviceName)} · ${routeText}</p>
        </div>

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

    const form = document.getElementById('login-form');
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      const serverUrl = document.getElementById('login-server').value.trim();
      const username = document.getElementById('login-user').value.trim();
      const password = document.getElementById('login-pass').value;

      view.serverUrl = serverUrl;
      view.username = username;
      view.error = '';
      view.submitting = true;
      render();

      try {
        const result = await api.authenticateByName(serverUrl, username, password);
        await Promise.all([
          window.UnraidHub.storageSet(STORAGE_KEYS.ACCESS_TOKEN, result.accessToken),
          window.UnraidHub.storageSet(STORAGE_KEYS.USER_ID, result.userId),
          window.UnraidHub.storageSet(STORAGE_KEYS.USER_NAME, result.userName),
          window.UnraidHub.storageSet(STORAGE_KEYS.CUSTOM_SERVER_URL, result.baseUrl)
        ]);
        state.customServerUrl = result.baseUrl;
        state.userName = result.userName;
        state.isAuthenticated = true;

        showToast(`${result.userName}님으로 로그인되었습니다.`, 'success');
        state.stack = [];
        pushView({ type: 'home' });
        await loadHomeData(false);
      } catch (err) {
        view.submitting = false;
        view.error = err.message || '로그인에 실패했습니다. 서버 주소와 계정을 확인해 주세요.';
        reportPluginError('Login', view.error, err);
        render();
      }
    });
  }

  function renderHomeView() {
    const { loading, error, resume, libraries, latest } = state.homeData;
    const itemLookup = new Map();
    resume.forEach((it) => itemLookup.set(it.Id, it));
    latest.forEach((it) => itemLookup.set(it.Id, it));

    let bodyHtml = '';
    if (error) {
      bodyHtml += `
        <div class="error-banner">
          <span>${escapeHtml(error)}</span>
          <button class="error-banner-btn" data-action="refresh">재시도</button>
        </div>
      `;
    }

    if (loading && resume.length === 0 && libraries.length === 0 && latest.length === 0) {
      bodyHtml += `
        <div class="center-state">
          <div class="spinner"></div>
          <p class="boot-text">Emby 라이브러리를 불러오는 중...</p>
        </div>
      `;
    } else {
      // 1) 이어보기
      if (resume.length > 0) {
        const cards = resume.map((item) => {
          const thumbUrl = api.getLandscapeImageUrl(item, 540);
          const pct = getProgressPercent(item);
          const isEp = item.Type === 'Episode';
          const title = isEp ? (item.SeriesName || item.Name) : item.Name;
          const sub = isEp
            ? [window.EmbyUtils.formatEpisodeCode(item), item.Name].filter(Boolean).join(' · ')
            : `${Math.round(pct)}% 시청`;

          return `
            <div class="resume-card" data-open-item-id="${escapeHtml(item.Id)}">
              <div class="thumb-16-9">
                ${renderFallbackImage(thumbUrl, title, title)}
                <button class="resume-play-badge" data-quick-play-id="${escapeHtml(item.Id)}" aria-label="바로 이어보기">
                  ${ICONS.play}
                </button>
                <div class="progress-track"><div class="progress-fill" style="width:${pct.toFixed(1)}%"></div></div>
              </div>
              <div class="resume-meta">
                <p class="card-title">${escapeHtml(title || '제목 없음')}</p>
                <p class="card-sub">${escapeHtml(sub || '')}</p>
              </div>
            </div>
          `;
        }).join('');

        bodyHtml += `
          <section class="section">
            <div class="section-header">
              <h2 class="section-title">이어보기</h2>
              <span class="section-count">${resume.length}개</span>
            </div>
            <div class="h-scroll">${cards}</div>
          </section>
        `;
      }

      // 2) 내 라이브러리
      if (libraries.length > 0) {
        const libCards = libraries.map((lib) => `
          <button class="library-card" data-open-library-id="${escapeHtml(lib.Id)}">
            <span class="library-type">${escapeHtml(getCollectionTypeLabel(lib.CollectionType))}</span>
            <span class="library-name">${escapeHtml(lib.Name)}</span>
          </button>
        `).join('');

        bodyHtml += `
          <section class="section">
            <div class="section-header">
              <h2 class="section-title">라이브러리</h2>
              <span class="section-count">${libraries.length}개</span>
            </div>
            <div class="h-scroll">${libCards}</div>
          </section>
        `;
      }

      // 3) 최근 추가된 항목
      if (latest.length > 0) {
        const latestCards = latest.map((item) => renderPosterCard(item, false)).join('');
        bodyHtml += `
          <section class="section">
            <div class="section-header">
              <h2 class="section-title">최근 추가된 항목</h2>
              <span class="section-count">${latest.length}개</span>
            </div>
            <div class="h-scroll">${latestCards}</div>
          </section>
        `;
      }

      if (!loading && resume.length === 0 && libraries.length === 0 && latest.length === 0 && !error) {
        bodyHtml += `
          <div class="center-state">
            <p class="state-title">표시할 항목이 없습니다</p>
            <p class="state-desc">Emby 서버에 등록된 라이브러리가 없거나 권한이 제한되어 있습니다.</p>
            <button class="secondary-btn" data-action="refresh">새로고침</button>
          </div>
        `;
      }
    }

    appEl.innerHTML = `
      ${renderTopBar({ title: state.context.serviceName || 'Emby', showBack: false, showSearch: true, showRefresh: true })}
      <main>${bodyHtml}</main>
    `;

    bindFallbackImages();
    bindCommonActions();

    appEl.querySelectorAll('[data-open-library-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const lib = libraries.find((l) => l.Id === btn.dataset.openLibraryId);
        if (lib) openLibrary(lib);
      });
    });

    appEl.querySelectorAll('[data-quick-play-id]').forEach((btn) => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const item = itemLookup.get(btn.dataset.quickPlayId);
        if (item) playEmbyItem(item, true);
      });
    });

    appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const item = itemLookup.get(btn.dataset.openItemId);
        if (item) openItem(item);
      });
    });
  }

  function renderLibraryView(view) {
    const sortChips = SORT_OPTIONS.map((opt) => `
      <button class="chip-btn ${view.sortKey === opt.key ? 'active' : ''}" data-sort-key="${opt.key}">
        ${escapeHtml(opt.label)}
      </button>
    `).join('');

    let bodyHtml = `<div class="sort-bar">${sortChips}</div>`;

    if (view.error) {
      bodyHtml += `
        <div class="error-banner">
          <span>${escapeHtml(view.error)}</span>
          <button class="error-banner-btn" data-action="refresh">재시도</button>
        </div>
      `;
    } else if (view.loading) {
      bodyHtml += `
        <div class="center-state">
          <div class="spinner"></div>
          <p class="boot-text">목록을 불러오는 중...</p>
        </div>
      `;
    } else if (view.items.length === 0) {
      bodyHtml += `
        <div class="center-state">
          <p class="state-title">항목이 없습니다</p>
          <p class="state-desc">이 라이브러리에 표시할 미디어가 없습니다.</p>
        </div>
      `;
    } else {
      const cards = view.items.map((item) => renderPosterCard(item, true)).join('');
      bodyHtml += `<div class="poster-grid">${cards}</div>`;
    }

    appEl.innerHTML = `
      ${renderTopBar({ title: view.library.Name || '라이브러리', showBack: true, showSearch: true, showRefresh: true })}
      <main>${bodyHtml}</main>
    `;

    bindFallbackImages();
    bindCommonActions();

    appEl.querySelectorAll('[data-sort-key]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const nextSort = btn.dataset.sortKey;
        if (nextSort === view.sortKey) return;
        view.sortKey = nextSort;
        state.librarySort = nextSort;
        window.UnraidHub.storageSet(STORAGE_KEYS.LIBRARY_SORT, nextSort).catch(() => {});
        await loadLibraryItems(view);
      });
    });

    appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const item = view.items.find((it) => it.Id === btn.dataset.openItemId);
        if (item) openItem(item);
      });
    });
  }

  function renderSearchView(view) {
    appEl.innerHTML = `
      ${renderTopBar({ title: '검색', showBack: true, showSearch: false, showRefresh: false })}
      <main>
        <div class="search-bar-wrap">
          <div class="search-input-box">
            <span style="width:20px;height:20px;color:var(--text-muted);display:inline-flex;">${ICONS.search}</span>
            <input id="emby-search-input" type="search" placeholder="영화, 시리즈, 에피소드 제목 검색..." value="${escapeHtml(view.query || '')}" autofocus />
            <button id="emby-search-clear" class="icon-btn ${view.query ? '' : 'hidden'}" aria-label="검색어 지우기">${ICONS.close}</button>
          </div>
        </div>
        <div id="search-results-container"></div>
      </main>
    `;

    bindCommonActions();
    renderSearchResultsOnly(view);

    const input = document.getElementById('emby-search-input');
    const clearBtn = document.getElementById('emby-search-clear');

    input.addEventListener('input', () => {
      const q = input.value;
      clearBtn.classList.toggle('hidden', !q);
      if (state.searchTimer) clearTimeout(state.searchTimer);
      state.searchTimer = setTimeout(() => {
        runSearch(view, q);
      }, 320);
    });

    clearBtn.addEventListener('click', () => {
      input.value = '';
      clearBtn.classList.add('hidden');
      input.focus();
      runSearch(view, '');
    });
  }

  function renderSearchResultsOnly(view) {
    const container = document.getElementById('search-results-container');
    if (!container) return;

    if (view.error) {
      container.innerHTML = `<div class="error-banner"><span>${escapeHtml(view.error)}</span></div>`;
      return;
    }

    if (!view.query.trim()) {
      container.innerHTML = `
        <div class="center-state">
          <p class="state-title">제목으로 미디어 검색</p>
          <p class="state-desc">검색어를 입력하면 영화·시리즈·에피소드를 실시간으로 찾습니다.</p>
        </div>
      `;
      return;
    }

    if (view.loading) {
      container.innerHTML = `
        <div class="center-state">
          <div class="spinner"></div>
          <p class="boot-text">'${escapeHtml(view.query)}' 검색 중...</p>
        </div>
      `;
      return;
    }

    if (view.results.length === 0) {
      container.innerHTML = `
        <div class="center-state">
          <p class="state-title">검색 결과가 없습니다</p>
          <p class="state-desc">'${escapeHtml(view.query)}'와 일치하는 항목을 찾지 못했습니다.</p>
        </div>
      `;
      return;
    }

    container.innerHTML = `
      <div class="section-header" style="padding-top:10px;">
        <h2 class="section-title">검색 결과</h2>
        <span class="section-count">${view.results.length}건</span>
      </div>
      <div class="poster-grid">${view.results.map((item) => renderPosterCard(item, true)).join('')}</div>
    `;

    bindFallbackImages(container);
    container.querySelectorAll('[data-open-item-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const item = view.results.find((it) => it.Id === btn.dataset.openItemId);
        if (item) openItem(item);
      });
    });
  }

  function renderSeriesView(view) {
    const series = view.series || {};
    const backdropUrl = api.getBackdropUrl(series, 960);
    const posterUrl = api.getPosterUrl(series, 400);

    const pills = [
      series.ProductionYear ? `${series.ProductionYear}년` : '',
      series.OfficialRating || '',
      series.CommunityRating ? `★ ${Number(series.CommunityRating).toFixed(1)}` : ''
    ].filter(Boolean);

    const seasonChips = view.seasons.map((season) => `
      <button class="chip-btn ${view.selectedSeasonId === season.Id ? 'active' : ''}" data-season-id="${escapeHtml(season.Id)}">
        ${escapeHtml(season.Name || `시즌 ${season.IndexNumber || 1}`)}
      </button>
    `).join('');

    let episodesHtml = '';
    if (view.episodesLoading || view.loading) {
      episodesHtml = `
        <div class="center-state" style="min-height:220px;">
          <div class="spinner"></div>
          <p class="boot-text">에피소드 목록을 불러오는 중...</p>
        </div>
      `;
    } else if (view.episodes.length === 0) {
      episodesHtml = `
        <div class="center-state" style="min-height:180px;">
          <p class="state-desc">표시할 에피소드가 없습니다.</p>
        </div>
      `;
    } else {
      episodesHtml = `<div class="episode-list">${view.episodes.map((ep) => {
        const thumbUrl = api.getLandscapeImageUrl(ep, 420);
        const code = window.EmbyUtils.formatEpisodeCode(ep);
        const pct = getProgressPercent(ep);
        const played = Boolean(ep.UserData && ep.UserData.Played);
        const runtime = formatRuntime(ep.RunTimeTicks);

        return `
          <div class="episode-card">
            <button class="episode-thumb-btn" data-open-ep-id="${escapeHtml(ep.Id)}">
              <div class="thumb-16-9">
                ${renderFallbackImage(thumbUrl, ep.Name, code || ep.Name)}
                ${played ? '<span class="poster-badge-top played">✓</span>' : ''}
                ${pct > 0 && !played ? `<div class="progress-track"><div class="progress-fill" style="width:${pct.toFixed(1)}%"></div></div>` : ''}
              </div>
            </button>
            <button class="episode-info" data-open-ep-id="${escapeHtml(ep.Id)}">
              <div class="episode-code">${escapeHtml(code || '에피소드')}</div>
              <p class="episode-name">${escapeHtml(ep.Name || '제목 없음')}</p>
              <p class="episode-sub">${escapeHtml([runtime, played ? '시청 완료' : (pct > 0 ? `${Math.round(pct)}% 시청` : '')].filter(Boolean).join(' · '))}</p>
            </button>
            <button class="episode-quick-play" data-play-ep-id="${escapeHtml(ep.Id)}" aria-label="에피소드 바로 재생">
              <span style="width:18px;height:18px;display:inline-flex;">${ICONS.play}</span>
            </button>
          </div>
        `;
      }).join('')}</div>`;
    }

    appEl.innerHTML = `
      ${renderTopBar({ title: series.Name || '시리즈', showBack: true, showSearch: true, showRefresh: true })}
      <main>
        ${view.error ? `<div class="error-banner"><span>${escapeHtml(view.error)}</span></div>` : ''}

        <section class="hero-wrap">
          ${backdropUrl ? `<img class="hero-backdrop" src="${escapeHtml(backdropUrl)}" alt="" data-fallback-img="1" />` : ''}
          <div class="hero-gradient"></div>
          <div class="hero-content">
            <div class="hero-poster">
              <div class="poster-2-3">${renderFallbackImage(posterUrl, series.Name, series.Name)}</div>
            </div>
            <div class="hero-info">
              <p class="hero-eyebrow">TV 시리즈</p>
              <h2 class="hero-title">${escapeHtml(series.Name || '')}</h2>
              ${pills.length > 0 ? `<div class="meta-pills">${pills.map((p) => `<span class="meta-pill ${p.startsWith('★') ? 'rating' : ''}">${escapeHtml(p)}</span>`).join('')}</div>` : ''}
            </div>
          </div>
        </section>

        ${series.Overview ? `<div class="detail-body" style="padding-bottom:6px;"><p class="overview-text">${escapeHtml(series.Overview)}</p></div>` : ''}
        ${seasonChips ? `<div class="sort-bar">${seasonChips}</div>` : ''}
        ${episodesHtml}
      </main>
    `;

    bindFallbackImages();
    bindCommonActions();

    appEl.querySelectorAll('[data-season-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const sid = btn.dataset.seasonId;
        if (sid !== view.selectedSeasonId) {
          loadSeriesEpisodes(view, sid);
        }
      });
    });

    appEl.querySelectorAll('[data-open-ep-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const ep = view.episodes.find((e) => e.Id === btn.dataset.openEpId);
        if (ep) openDetail(ep.Id, ep);
      });
    });

    appEl.querySelectorAll('[data-play-ep-id]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const ep = view.episodes.find((e) => e.Id === btn.dataset.playEpId);
        if (ep) playEmbyItem(ep, true);
      });
    });
  }

  function renderDetailView(view) {
    const item = view.item || {};
    const backdropUrl = api.getBackdropUrl(item, 960);
    const posterUrl = api.getPosterUrl(item, 400);
    const resumeSec = Math.floor(((item.UserData && item.UserData.PlaybackPositionTicks) || 0) / window.EmbyUtils.TICKS_PER_SECOND);
    const hasResume = resumeSec >= 5;

    const mediaSource = (Array.isArray(item.MediaSources) && item.MediaSources[0]) || null;
    const streams = (mediaSource && Array.isArray(mediaSource.MediaStreams))
      ? mediaSource.MediaStreams
      : (Array.isArray(item.MediaStreams) ? item.MediaStreams : []);
    const videoStream = streams.find((s) => s.Type === 'Video');
    const audioStreams = streams.filter((s) => s.Type === 'Audio');
    const subStreams = streams.filter((s) => s.Type === 'Subtitle');

    const pills = [
      item.ProductionYear ? `${item.ProductionYear}년` : '',
      formatRuntime(item.RunTimeTicks),
      item.OfficialRating || '',
      item.CommunityRating ? `★ ${Number(item.CommunityRating).toFixed(1)}` : '',
      videoStream && videoStream.Height ? `${videoStream.Height}p` : '',
      videoStream && videoStream.Codec ? String(videoStream.Codec).toUpperCase() : ''
    ].filter(Boolean);

    const isEpisode = item.Type === 'Episode';
    const eyebrow = isEpisode
      ? [item.SeriesName, window.EmbyUtils.formatEpisodeCode(item)].filter(Boolean).join(' · ')
      : (item.Type === 'Movie' ? '영화' : '비디오');

    // 이전화 / 다음화 계산
    let prevEp = null;
    let nextEp = null;
    if (isEpisode && Array.isArray(view.siblingEpisodes) && view.siblingEpisodes.length > 0) {
      const idx = view.siblingEpisodes.findIndex((e) => e.Id === item.Id);
      if (idx > 0) prevEp = view.siblingEpisodes[idx - 1];
      if (idx >= 0 && idx < view.siblingEpisodes.length - 1) nextEp = view.siblingEpisodes[idx + 1];
    }

    const audioHtml = audioStreams.length > 0
      ? audioStreams.map((a) => `
          <div class="stream-item">
            <span>${escapeHtml(a.DisplayTitle || a.Title || a.Language || '오디오 트랙')}</span>
            <span class="stream-tag">${escapeHtml([a.Codec && a.Codec.toUpperCase(), a.ChannelLayout || (a.Channels ? `${a.Channels}ch` : '')].filter(Boolean).join(' · ') || 'AUDIO')}</span>
          </div>
        `).join('')
      : '<p class="state-desc" style="text-align:left;">오디오 트랙 정보 없음</p>';

    const subHtml = subStreams.length > 0
      ? subStreams.map((s) => `
          <div class="stream-item">
            <span>${escapeHtml(s.DisplayTitle || s.Title || s.Language || '자막 트랙')}</span>
            <span class="stream-tag sub">${escapeHtml([s.Codec && s.Codec.toUpperCase(), s.IsExternal ? '외부' : '내장'].filter(Boolean).join(' · '))}</span>
          </div>
        `).join('')
      : '<p class="state-desc" style="text-align:left;">자막 트랙 정보 없음</p>';

    appEl.innerHTML = `
      ${renderTopBar({ title: item.Name || '상세 정보', showBack: true, showSearch: true, showRefresh: true })}
      <main>
        ${view.error ? `<div class="error-banner"><span>${escapeHtml(view.error)}</span></div>` : ''}

        <section class="hero-wrap">
          ${backdropUrl ? `<img class="hero-backdrop" src="${escapeHtml(backdropUrl)}" alt="" data-fallback-img="1" />` : ''}
          <div class="hero-gradient"></div>
          <div class="hero-content">
            <div class="hero-poster">
              <div class="poster-2-3">${renderFallbackImage(posterUrl, item.Name, item.Name)}</div>
            </div>
            <div class="hero-info">
              <p class="hero-eyebrow">${escapeHtml(eyebrow)}</p>
              <h2 class="hero-title">${escapeHtml(item.Name || '')}</h2>
              ${pills.length > 0 ? `<div class="meta-pills">${pills.map((p) => `<span class="meta-pill ${p.startsWith('★') ? 'rating' : ''}">${escapeHtml(p)}</span>`).join('')}</div>` : ''}
            </div>
          </div>
        </section>

        <div class="detail-body">
          ${view.loading && !item.MediaSources ? `
            <div class="center-state" style="min-height:140px;">
              <div class="spinner"></div>
              <p class="boot-text">상세 스트림 정보를 불러오는 중...</p>
            </div>
          ` : `
            <div class="cta-stack">
              ${hasResume ? `
                <button class="primary-btn" id="btn-play-resume">
                  <span style="width:18px;height:18px;display:inline-flex;">${ICONS.play}</span>
                  <span>이어보기 (${formatTimestamp(resumeSec)}부터 · libmpv)</span>
                </button>
                <button class="secondary-btn" id="btn-play-start">처음부터 재생 (Direct Play)</button>
              ` : `
                <button class="primary-btn" id="btn-play-start">
                  <span style="width:18px;height:18px;display:inline-flex;">${ICONS.play}</span>
                  <span>재생하기 (libmpv Direct Play)</span>
                </button>
              `}

              ${(prevEp || nextEp) ? `
                <div class="cta-row">
                  <button class="secondary-btn" id="btn-prev-ep" ${prevEp ? '' : 'disabled'}>이전화</button>
                  <button class="secondary-btn" id="btn-next-ep" ${nextEp ? '' : 'disabled'}>다음화</button>
                </div>
              ` : ''}
            </div>

            ${item.Overview ? `<p class="overview-text">${escapeHtml(item.Overview)}</p>` : ''}

            <div class="stream-card">
              <h3 class="stream-heading">오디오 스트림 (${audioStreams.length})</h3>
              <div class="stream-list">${audioHtml}</div>
            </div>

            <div class="stream-card">
              <h3 class="stream-heading">자막 스트림 (${subStreams.length})</h3>
              <div class="stream-list">${subHtml}</div>
            </div>
          `}
        </div>
      </main>
    `;

    bindFallbackImages();
    bindCommonActions();

    const resumeBtn = document.getElementById('btn-play-resume');
    if (resumeBtn) {
      resumeBtn.addEventListener('click', () => playEmbyItem(item, true));
    }
    const startBtn = document.getElementById('btn-play-start');
    if (startBtn) {
      startBtn.addEventListener('click', () => playEmbyItem(item, false));
    }
    const prevBtn = document.getElementById('btn-prev-ep');
    if (prevBtn && prevEp) {
      prevBtn.addEventListener('click', () => openDetail(prevEp.Id, prevEp, true));
    }
    const nextBtn = document.getElementById('btn-next-ep');
    if (nextBtn && nextEp) {
      nextBtn.addEventListener('click', () => openDetail(nextEp.Id, nextEp, true));
    }
  }

  // =========================================================================
  // 설정 모달 (서버 주소 변경 / 로그아웃 / 일반 웹뷰 전환)
  // =========================================================================

  function openSettingsModal() {
    const routeText = state.context.isTailscaleActive ? 'Tailscale 프록시 경유' : '홈 네트워크 직접 연결';
    modalRoot.innerHTML = `
      <div class="modal-sheet" id="settings-sheet">
        <div class="modal-header">
          <h2 class="modal-title">Emby 플러그인 설정</h2>
          <button class="icon-btn" id="modal-close-btn" aria-label="닫기">${ICONS.close}</button>
        </div>

        <div class="info-kv">
          <div class="info-kv-row">
            <span class="info-kv-key">로그인 계정</span>
            <span class="info-kv-val">${escapeHtml(state.userName || '알 수 없음')}</span>
          </div>
          <div class="info-kv-row">
            <span class="info-kv-key">연결 경로</span>
            <span class="info-kv-val">${routeText}</span>
          </div>
          <div class="info-kv-row">
            <span class="info-kv-key">현재 활성 주소</span>
            <span class="info-kv-val">${escapeHtml(api.baseUrl || '-')}</span>
          </div>
        </div>

        <div class="form-field">
          <label class="form-label" for="settings-server-url">Emby 서버 URL 변경</label>
          <input id="settings-server-url" class="text-input" type="url" value="${escapeHtml(api.baseUrl || '')}" placeholder="http://192.168.0.10:8096" />
        </div>

        <div class="cta-row">
          <button class="primary-btn" id="settings-save-url">주소 저장</button>
          <button class="secondary-btn" id="settings-reset-url">자동 주소 복원</button>
        </div>

        <div class="cta-row">
          <button class="secondary-btn" id="settings-open-webview">일반 웹뷰로 열기</button>
          <button class="secondary-btn" id="settings-close-plugin">런처로 복귀</button>
        </div>

        <button class="danger-btn" id="settings-logout">로그아웃</button>
      </div>
    `;
    modalRoot.classList.remove('hidden');

    modalRoot.onclick = (e) => {
      if (e.target === modalRoot) closeSettingsModal();
    };
    document.getElementById('modal-close-btn').onclick = closeSettingsModal;

    document.getElementById('settings-save-url').onclick = async () => {
      const nextUrl = window.EmbyUtils.normalizeBaseUrl(document.getElementById('settings-server-url').value);
      if (!nextUrl) {
        showToast('올바른 서버 URL을 입력해 주세요.', 'error');
        return;
      }
      state.customServerUrl = nextUrl;
      api.configure({ baseUrl: nextUrl });
      await window.UnraidHub.storageSet(STORAGE_KEYS.CUSTOM_SERVER_URL, nextUrl);
      closeSettingsModal();
      showToast('Emby 서버 주소가 변경되었습니다.', 'success');
      loadHomeData(false);
    };

    document.getElementById('settings-reset-url').onclick = async () => {
      state.customServerUrl = '';
      await window.UnraidHub.storageRemove(STORAGE_KEYS.CUSTOM_SERVER_URL);
      const autoUrl = resolveActiveBaseUrl('', state.context);
      api.configure({ baseUrl: autoUrl });
      closeSettingsModal();
      showToast('자동 감지된 도커 주소로 복원되었습니다.', 'success');
      loadHomeData(false);
    };

    document.getElementById('settings-open-webview').onclick = () => {
      closeSettingsModal();
      if (window.UnraidHub && window.UnraidHub.openWebView) window.UnraidHub.openWebView();
    };

    document.getElementById('settings-close-plugin').onclick = () => {
      closeSettingsModal();
      if (window.UnraidHub && window.UnraidHub.close) window.UnraidHub.close();
    };

    document.getElementById('settings-logout').onclick = async () => {
      await Promise.all([
        window.UnraidHub.storageRemove(STORAGE_KEYS.ACCESS_TOKEN),
        window.UnraidHub.storageRemove(STORAGE_KEYS.USER_ID),
        window.UnraidHub.storageRemove(STORAGE_KEYS.USER_NAME)
      ]);
      api.configure({ accessToken: '', userId: '' });
      state.isAuthenticated = false;
      state.userName = '';
      state.homeData = { loading: false, error: '', resume: [], libraries: [], latest: [] };
      closeSettingsModal();
      showToast('로그아웃되었습니다.');
      state.stack = [];
      pushView({
        type: 'login',
        serverUrl: api.baseUrl || state.context.activeBaseUrl || '',
        username: '',
        error: '',
        submitting: false
      });
    };
  }

  function closeSettingsModal() {
    modalRoot.classList.add('hidden');
    modalRoot.innerHTML = '';
  }

  // =========================================================================
  // 공통 버튼 바인딩 & 메인 렌더 분기
  // =========================================================================

  function bindCommonActions() {
    const backBtn = appEl.querySelector('[data-action="back"]');
    if (backBtn) backBtn.addEventListener('click', navigateBack);

    const searchBtn = appEl.querySelector('[data-action="open-search"]');
    if (searchBtn) searchBtn.addEventListener('click', openSearch);

    const settingsBtn = appEl.querySelector('[data-action="open-settings"]');
    if (settingsBtn) settingsBtn.addEventListener('click', openSettingsModal);

    appEl.querySelectorAll('[data-action="refresh"]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const top = currentView();
        if (!top) return;
        if (top.type === 'home') loadHomeData(false);
        else if (top.type === 'library') loadLibraryItems(top);
        else if (top.type === 'series') loadSeriesEpisodes(top, top.selectedSeasonId, false);
        else if (top.type === 'detail') loadDetailData(top, false);
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

  function render() {
    const view = currentView();
    if (!view) return;
    switch (view.type) {
      case 'login':
        renderLoginView(view);
        bindCommonActions();
        break;
      case 'home':
        renderHomeView();
        break;
      case 'library':
        renderLibraryView(view);
        break;
      case 'search':
        renderSearchView(view);
        break;
      case 'series':
        renderSeriesView(view);
        break;
      case 'detail':
        renderDetailView(view);
        break;
      default:
        break;
    }
  }

  // =========================================================================
  // 앱 초기화 부트스트랩
  // =========================================================================

  async function bootstrap() {
    try {
      if (!window.UnraidHub) {
        throw new Error('window.UnraidHub 네이티브 브릿지를 찾을 수 없습니다.');
      }

      const ctx = await window.UnraidHub.getContext();
      state.context = ctx || state.context;

      let [accessToken, userId, userName, customServerUrl, deviceId, librarySort] = await Promise.all([
        window.UnraidHub.storageGet(STORAGE_KEYS.ACCESS_TOKEN),
        window.UnraidHub.storageGet(STORAGE_KEYS.USER_ID),
        window.UnraidHub.storageGet(STORAGE_KEYS.USER_NAME),
        window.UnraidHub.storageGet(STORAGE_KEYS.CUSTOM_SERVER_URL),
        window.UnraidHub.storageGet(STORAGE_KEYS.DEVICE_ID),
        window.UnraidHub.storageGet(STORAGE_KEYS.LIBRARY_SORT)
      ]);

      if (!deviceId) {
        deviceId = `unraidhub_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
        await window.UnraidHub.storageSet(STORAGE_KEYS.DEVICE_ID, deviceId);
      }

      state.customServerUrl = customServerUrl || '';
      state.userName = userName || '';
      state.librarySort = librarySort || 'name_asc';

      const baseUrl = resolveActiveBaseUrl(state.customServerUrl, state.context);
      api.configure({
        baseUrl,
        accessToken: accessToken || '',
        userId: userId || '',
        deviceId
      });

      if (accessToken && userId && baseUrl) {
        state.isAuthenticated = true;
        pushView({ type: 'home' });
        await loadHomeData(false);
      } else {
        state.isAuthenticated = false;
        pushView({
          type: 'login',
          serverUrl: baseUrl,
          username: state.userName || '',
          error: '',
          submitting: false
        });
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

  document.addEventListener('DOMContentLoaded', bootstrap);
})();
