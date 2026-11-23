/**
 * 홈 화면 (이어보기 · 다음 에피소드 · 라이브러리 · 최근 추가 · 즐겨찾기 · 컬렉션 · 재생목록)
 */
import { api } from '../api.js';
import { state, triggerRender } from '../store.js';
import { ICONS } from '../icons.js';
import {
  escapeHtml,
  getCollectionTypeLabel,
  bindFallbackImages,
  attachLongPress
} from '../utils.js';
import {
  renderTopBar,
  renderPosterCard,
  renderLandscapeCard,
  openQuickItemActionsSheet
} from '../components/cards.js';
import { showToast, reportPluginError } from '../components/toast.js';
import { openCreatePlaylistModal } from '../components/playlist-modal.js';

export async function loadHomeData(silent = false) {
  if (!silent) {
    state.homeData.loading = true;
    state.homeData.error = '';
    triggerRender();
  }

  try {
    const safeFetch = (promise, fallback = []) => promise.catch(() => fallback);
    const [resume, nextUp, libraries, latest, favorites, collections, playlists] = await Promise.all([
      api.getResumeItems(18),
      safeFetch(api.getNextUpItems(18), []),
      api.getLibraries(),
      api.getLatestItems('', 24),
      safeFetch(api.getFavoriteItems(20), []),
      safeFetch(api.getCollections(24), []),
      safeFetch(api.getPlaylists(30), [])
    ]);

    state.homeData = {
      loading: false,
      error: '',
      resume,
      nextUp,
      libraries,
      latest,
      favorites,
      collections,
      playlists
    };
  } catch (err) {
    state.homeData.loading = false;
    state.homeData.error = err.message || 'Emby 홈 데이터를 불러오지 못했습니다.';
    reportPluginError('HomeLoad', state.homeData.error, err);
    if (err.isAuthError) {
      showToast('인증이 만료되었습니다. 설정에서 다시 로그인해 주세요.', 'error');
    }
  }
  triggerRender();
}

export function renderHomeView(appEl, {
  bindCommonActions,
  onOpenLibrary,
  onOpenItem,
  onPlayItem
}) {
  const {
    loading,
    error,
    resume,
    nextUp,
    libraries,
    latest,
    favorites,
    collections,
    playlists
  } = state.homeData;

  const itemLookup = new Map();
  [resume, nextUp, latest, favorites, collections, playlists].forEach((arr) => {
    arr.forEach((it) => {
      if (it && it.Id) itemLookup.set(it.Id, it);
    });
  });

  const isAllEmpty = resume.length === 0 && nextUp.length === 0 && libraries.length === 0 &&
    latest.length === 0 && favorites.length === 0 && collections.length === 0 && playlists.length === 0;

  let bodyHtml = '';
  if (error) {
    bodyHtml += `
      <div class="error-banner">
        <span>${escapeHtml(error)}</span>
        <button class="error-banner-btn" data-action="refresh">재시도</button>
      </div>
    `;
  }

  if (loading && isAllEmpty) {
    bodyHtml += `
      <div class="center-state">
        <div class="spinner"></div>
        <p class="boot-text">Emby 라이브러리를 불러오는 중...</p>
      </div>
    `;
  } else {
    // 1) 이어보기
    if (resume.length > 0) {
      const cards = resume.map((item) => renderLandscapeCard(item, { showPlayBtn: true, showProgress: true })).join('');
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

    // 2) 다음 에피소드 (Next Up)
    if (nextUp.length > 0) {
      const cards = nextUp.map((item) => renderLandscapeCard(item, {
        showPlayBtn: true,
        showProgress: false,
        eyebrowLabel: '다음 화'
      })).join('');
      bodyHtml += `
        <section class="section">
          <div class="section-header">
            <h2 class="section-title">다음 에피소드</h2>
            <span class="section-count">${nextUp.length}개</span>
          </div>
          <div class="h-scroll">${cards}</div>
        </section>
      `;
    }

    // 3) 라이브러리
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

    // 4) 최근 추가된 항목
    if (latest.length > 0) {
      const latestCards = latest.map((item) => renderPosterCard(item)).join('');
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

    // 5) 즐겨찾기
    if (favorites.length > 0) {
      const favCards = favorites.map((item) => renderPosterCard(item)).join('');
      bodyHtml += `
        <section class="section">
          <div class="section-header">
            <h2 class="section-title">즐겨찾기</h2>
            <span class="section-count">${favorites.length}개</span>
          </div>
          <div class="h-scroll">${favCards}</div>
        </section>
      `;
    }

    // 6) 컬렉션
    if (collections.length > 0) {
      const colCards = collections.map((item) => renderPosterCard(item)).join('');
      bodyHtml += `
        <section class="section">
          <div class="section-header">
            <h2 class="section-title">컬렉션</h2>
            <span class="section-count">${collections.length}개</span>
          </div>
          <div class="h-scroll">${colCards}</div>
        </section>
      `;
    }

    // 7) 재생목록 (생성 버튼 포함)
    const playlistCards = playlists.map((item) => renderPosterCard(item)).join('');
    bodyHtml += `
      <section class="section">
        <div class="section-header">
          <h2 class="section-title">재생목록</h2>
          <button type="button" class="section-action-btn" id="home-create-playlist">
            <span class="mini-icon">${ICONS.plus}</span>
            <span>새 재생목록</span>
          </button>
        </div>
        ${playlists.length > 0
          ? `<div class="h-scroll">${playlistCards}</div>`
          : '<p class="section-empty-hint">아직 재생목록이 없습니다. 항목을 길게 누르거나 상세 화면에서 추가해 보세요.</p>'
        }
      </section>
    `;

    if (!loading && isAllEmpty && !error) {
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

  bindFallbackImages(appEl);
  bindCommonActions();

  const createPlBtn = document.getElementById('home-create-playlist');
  if (createPlBtn) {
    createPlBtn.addEventListener('click', () => {
      openCreatePlaylistModal({ onCreated: () => loadHomeData(true) });
    });
  }

  appEl.querySelectorAll('[data-open-library-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const lib = libraries.find((l) => l.Id === btn.dataset.openLibraryId);
      if (lib) onOpenLibrary(lib);
    });
  });

  appEl.querySelectorAll('[data-quick-play-id]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const item = itemLookup.get(btn.dataset.quickPlayId);
      if (item) onPlayItem(item, { resume: true });
    });
  });

  appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const item = itemLookup.get(btn.dataset.openItemId);
      if (item) onOpenItem(item);
    });
  });

  // 카드 길게 누르기 빠른 액션 시트 (시청 완료 / 즐겨찾기 / 재생목록)
  attachLongPress(appEl, '[data-item-card]', (cardEl) => {
    const item = itemLookup.get(cardEl.dataset.itemCard);
    if (!item) return;
    openQuickItemActionsSheet(item, {
      onPlay: (it) => onPlayItem(it, { resume: true }),
      onOpenDetail: (it) => onOpenItem(it),
      onMutated: () => loadHomeData(true)
    });
  });
}
