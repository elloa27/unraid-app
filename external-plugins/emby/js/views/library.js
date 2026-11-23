/**
 * 라이브러리 / 컬렉션 화면
 * - 정렬 기준(이름순/추가된 날짜순/개봉일순/평점순/랜덤) + 오름차순/내림차순 전환
 * - 필터 칩(전체/미시청만/시청 완료/즐겨찾기/장르별/연도별)
 * - StartIndex·Limit 기반 페이지네이션 (무한 스크롤 + '더 보기' 버튼)
 */
import { api } from '../api.js';
import { state, savePref, triggerRender } from '../store.js';
import { ICONS } from '../icons.js';
import { escapeHtml, bindFallbackImages, attachLongPress } from '../utils.js';
import { renderTopBar, renderPosterCard, openQuickItemActionsSheet } from '../components/cards.js';
import { openSheet } from '../components/modal.js';
import { reportPluginError } from '../components/toast.js';

const PAGE_LIMIT = 48;

const SORT_OPTIONS = [
  { key: 'name', label: '이름순' },
  { key: 'date_added', label: '추가된 날짜순' },
  { key: 'release', label: '개봉일순' },
  { key: 'rating', label: '평점순' },
  { key: 'random', label: '랜덤' }
];

const STATUS_FILTERS = [
  { key: 'all', label: '전체' },
  { key: 'unplayed', label: '미시청만' },
  { key: 'played', label: '시청 완료' },
  { key: 'favorite', label: '즐겨찾기' }
];

let activeObserver = null;

export function createLibraryViewState(library) {
  return {
    type: 'library',
    library,
    sortBy: state.prefs.librarySortBy || 'name',
    sortOrder: state.prefs.librarySortOrder || 'Ascending',
    filterStatus: 'all',
    selectedGenre: null, // { Id, Name }
    selectedYear: '', // e.g. '2024'
    genresCache: null,
    yearsCache: null,
    items: [],
    totalRecordCount: 0,
    loading: true,
    loadingMore: false,
    error: ''
  };
}

export async function loadLibraryItems(view, { reset = true } = {}) {
  if (reset) {
    view.loading = true;
    view.loadingMore = false;
    view.error = '';
    view.items = [];
    view.totalRecordCount = 0;
    triggerRender();
  } else {
    if (view.loadingMore || view.loading || view.items.length >= view.totalRecordCount) return;
    view.loadingMore = true;
    triggerRender();
  }

  try {
    const startIndex = reset ? 0 : view.items.length;
    const result = await api.getLibraryItems({
      library: view.library,
      sortBy: view.sortBy,
      sortOrder: view.sortOrder,
      filterStatus: view.filterStatus,
      genreIds: view.selectedGenre ? [view.selectedGenre.Id] : [],
      years: view.selectedYear ? [view.selectedYear] : [],
      startIndex,
      limit: PAGE_LIMIT
    });

    view.items = reset ? result.items : [...view.items, ...result.items];
    view.totalRecordCount = result.totalRecordCount;
    view.loading = false;
    view.loadingMore = false;
  } catch (err) {
    view.loading = false;
    view.loadingMore = false;
    view.error = err.message || '라이브러리 항목을 불러오지 못했습니다.';
    reportPluginError('LibraryLoad', view.error, err);
  }
  triggerRender();
}

async function openGenrePickerSheet(view) {
  openSheet({
    title: '장르 필터',
    subtitle: view.library.Name || '',
    bodyHtml: '<div id="genre-picker-body"><div class="center-state compact"><div class="spinner"></div><p class="boot-text">장르 목록 불러오는 중...</p></div></div>',
    onMount: async (el, close) => {
      const body = el.querySelector('#genre-picker-body');
      try {
        if (!Array.isArray(view.genresCache)) {
          view.genresCache = await api.getGenres(view.library.Id);
        }
        const genres = view.genresCache;
        if (genres.length === 0) {
          body.innerHTML = '<p class="empty-inline">선택할 수 있는 장르 정보가 없습니다.</p>';
          return;
        }
        body.innerHTML = `
          <div class="picker-chip-wrap">
            <button type="button" class="chip-btn ${!view.selectedGenre ? 'active' : ''}" data-genre-id="">전체 장르</button>
            ${genres.map((g) => `
              <button type="button" class="chip-btn ${view.selectedGenre && view.selectedGenre.Id === g.Id ? 'active' : ''}" data-genre-id="${escapeHtml(g.Id)}" data-genre-name="${escapeHtml(g.Name)}">
                ${escapeHtml(g.Name)}
              </button>
            `).join('')}
          </div>
        `;
        body.querySelectorAll('[data-genre-id]').forEach((btn) => {
          btn.addEventListener('click', () => {
            const gid = btn.dataset.genreId;
            view.selectedGenre = gid ? { Id: gid, Name: btn.dataset.genreName } : null;
            close();
            loadLibraryItems(view, { reset: true });
          });
        });
      } catch (err) {
        body.innerHTML = `<div class="error-banner" style="margin:0;"><span>${escapeHtml(err.message || '장르 로드 실패')}</span></div>`;
        reportPluginError('LoadGenres', '장르 목록을 불러오지 못했습니다.', err);
      }
    }
  });
}

async function openYearPickerSheet(view) {
  openSheet({
    title: '연도 필터',
    subtitle: view.library.Name || '',
    bodyHtml: '<div id="year-picker-body"><div class="center-state compact"><div class="spinner"></div><p class="boot-text">연도 목록 불러오는 중...</p></div></div>',
    onMount: async (el, close) => {
      const body = el.querySelector('#year-picker-body');
      try {
        if (!Array.isArray(view.yearsCache)) {
          view.yearsCache = await api.getYears(view.library.Id);
        }
        const years = view.yearsCache;
        if (years.length === 0) {
          body.innerHTML = '<p class="empty-inline">선택할 수 있는 연도 정보가 없습니다.</p>';
          return;
        }
        body.innerHTML = `
          <div class="picker-chip-wrap">
            <button type="button" class="chip-btn ${!view.selectedYear ? 'active' : ''}" data-year-val="">전체 연도</button>
            ${years.map((y) => {
              const name = String(y.Name || '');
              return `
                <button type="button" class="chip-btn ${view.selectedYear === name ? 'active' : ''}" data-year-val="${escapeHtml(name)}">
                  ${escapeHtml(name)}년
                </button>
              `;
            }).join('')}
          </div>
        `;
        body.querySelectorAll('[data-year-val]').forEach((btn) => {
          btn.addEventListener('click', () => {
            view.selectedYear = btn.dataset.yearVal || '';
            close();
            loadLibraryItems(view, { reset: true });
          });
        });
      } catch (err) {
        body.innerHTML = `<div class="error-banner" style="margin:0;"><span>${escapeHtml(err.message || '연도 로드 실패')}</span></div>`;
        reportPluginError('LoadYears', '연도 목록을 불러오지 못했습니다.', err);
      }
    }
  });
}

export function renderLibraryView(appEl, view, {
  bindCommonActions,
  onOpenItem,
  onPlayItem,
  onOpenMoreMenu
}) {
  if (activeObserver) {
    activeObserver.disconnect();
    activeObserver = null;
  }

  const isRandom = view.sortBy === 'random';
  const orderLabel = view.sortOrder === 'Descending' ? '내림차순' : '오름차순';
  const orderIcon = view.sortOrder === 'Descending' ? ICONS.sortDesc : ICONS.sortAsc;

  const sortChips = SORT_OPTIONS.map((opt) => `
    <button class="chip-btn ${view.sortBy === opt.key ? 'active' : ''}" data-sort-by="${opt.key}">
      ${escapeHtml(opt.label)}
    </button>
  `).join('');

  const statusChips = STATUS_FILTERS.map((f) => `
    <button class="chip-btn ${view.filterStatus === f.key ? 'active' : ''}" data-filter-status="${f.key}">
      ${escapeHtml(f.label)}
    </button>
  `).join('');

  const hasCustomFilter = view.filterStatus !== 'all' || Boolean(view.selectedGenre) || Boolean(view.selectedYear);
  const cols = state.prefs.gridCols || 3;
  const hasMore = view.items.length < view.totalRecordCount;

  let bodyHtml = `
    <div class="library-controls">
      <div class="sort-bar">
        ${!isRandom ? `
          <button type="button" class="chip-btn order-toggle-btn" id="lib-order-toggle" aria-label="정렬 방향 전환">
            <span class="mini-icon">${orderIcon}</span>
            <span>${orderLabel}</span>
          </button>
        ` : ''}
        ${sortChips}
      </div>
      <div class="sort-bar filter-bar">
        ${statusChips}
        <button type="button" class="chip-btn ${view.selectedGenre ? 'active' : ''}" id="lib-genre-btn">
          장르${view.selectedGenre ? `: ${escapeHtml(view.selectedGenre.Name)}` : ''} ▾
        </button>
        <button type="button" class="chip-btn ${view.selectedYear ? 'active' : ''}" id="lib-year-btn">
          연도${view.selectedYear ? `: ${escapeHtml(view.selectedYear)}` : ''} ▾
        </button>
        ${hasCustomFilter ? '<button type="button" class="chip-btn reset-chip" id="lib-filter-reset">필터 초기화</button>' : ''}
      </div>
    </div>
  `;

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
        <p class="state-title">표시할 항목이 없습니다</p>
        <p class="state-desc">${hasCustomFilter ? '선택한 필터 조건과 일치하는 미디어가 없습니다.' : '이 라이브러리에 표시할 미디어가 없습니다.'}</p>
        ${hasCustomFilter ? '<button class="secondary-btn" id="empty-filter-reset">필터 초기화</button>' : ''}
      </div>
    `;
  } else {
    const cards = view.items.map((item) => renderPosterCard(item, { gridMode: true })).join('');
    bodyHtml += `
      <div class="library-summary-bar">
        <span>총 <strong>${view.totalRecordCount}</strong>개 항목 중 ${view.items.length}개 표시</span>
      </div>
      <div class="poster-grid cols-${cols}">${cards}</div>
      ${hasMore ? `
        <div class="load-more-wrap" id="lib-load-more-sentinel">
          <button type="button" class="secondary-btn load-more-btn" id="lib-load-more-btn" ${view.loadingMore ? 'disabled' : ''}>
            ${view.loadingMore ? '불러오는 중...' : `더 보기 (${view.items.length} / ${view.totalRecordCount})`}
          </button>
        </div>
      ` : ''}
    `;
  }

  const showMore = view.library && view.library.Type === 'BoxSet';

  appEl.innerHTML = `
    ${renderTopBar({ title: view.library.Name || '라이브러리', showBack: true, showSearch: true, showRefresh: true, showMore })}
    <main>${bodyHtml}</main>
  `;

  bindFallbackImages(appEl);
  bindCommonActions();

  if (showMore && onOpenMoreMenu) {
    const moreBtn = appEl.querySelector('[data-action="open-more"]');
    if (moreBtn) moreBtn.addEventListener('click', () => onOpenMoreMenu(view.library));
  }

  const orderBtn = document.getElementById('lib-order-toggle');
  if (orderBtn) {
    orderBtn.addEventListener('click', () => {
      const nextOrder = view.sortOrder === 'Ascending' ? 'Descending' : 'Ascending';
      view.sortOrder = nextOrder;
      savePref('librarySortOrder', nextOrder).catch(() => {});
      loadLibraryItems(view, { reset: true });
    });
  }

  appEl.querySelectorAll('[data-sort-by]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const nextSort = btn.dataset.sortBy;
      if (nextSort === view.sortBy && nextSort !== 'random') return;
      view.sortBy = nextSort;
      // 날짜/개봉일/평점은 기본 내림차순, 이름은 기본 오름차순으로 자연스럽게 설정
      if (nextSort === 'name') view.sortOrder = 'Ascending';
      else if (nextSort !== 'random') view.sortOrder = 'Descending';
      savePref('librarySortBy', view.sortBy).catch(() => {});
      savePref('librarySortOrder', view.sortOrder).catch(() => {});
      loadLibraryItems(view, { reset: true });
    });
  });

  appEl.querySelectorAll('[data-filter-status]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const nextStatus = btn.dataset.filterStatus;
      if (nextStatus === view.filterStatus) return;
      view.filterStatus = nextStatus;
      loadLibraryItems(view, { reset: true });
    });
  });

  const genreBtn = document.getElementById('lib-genre-btn');
  if (genreBtn) genreBtn.addEventListener('click', () => openGenrePickerSheet(view));

  const yearBtn = document.getElementById('lib-year-btn');
  if (yearBtn) yearBtn.addEventListener('click', () => openYearPickerSheet(view));

  const resetFilters = () => {
    view.filterStatus = 'all';
    view.selectedGenre = null;
    view.selectedYear = '';
    loadLibraryItems(view, { reset: true });
  };
  const resetBtn = document.getElementById('lib-filter-reset');
  if (resetBtn) resetBtn.addEventListener('click', resetFilters);
  const emptyResetBtn = document.getElementById('empty-filter-reset');
  if (emptyResetBtn) emptyResetBtn.addEventListener('click', resetFilters);

  const loadMoreBtn = document.getElementById('lib-load-more-btn');
  if (loadMoreBtn) {
    loadMoreBtn.addEventListener('click', () => loadLibraryItems(view, { reset: false }));
  }

  // IntersectionObserver 무한 스크롤
  const sentinel = document.getElementById('lib-load-more-sentinel');
  if (sentinel && hasMore && !view.loadingMore && typeof IntersectionObserver !== 'undefined') {
    activeObserver = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        if (activeObserver) {
          activeObserver.disconnect();
          activeObserver = null;
        }
        loadLibraryItems(view, { reset: false });
      }
    }, { rootMargin: '240px' });
    activeObserver.observe(sentinel);
  }

  const itemMap = new Map(view.items.map((it) => [it.Id, it]));
  appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const item = itemMap.get(btn.dataset.openItemId);
      if (item) onOpenItem(item);
    });
  });

  attachLongPress(appEl, '[data-item-card]', (cardEl) => {
    const item = itemMap.get(cardEl.dataset.itemCard);
    if (!item) return;
    openQuickItemActionsSheet(item, {
      onPlay: (it) => onPlayItem(it, { resume: true }),
      onOpenDetail: (it) => onOpenItem(it),
      onMutated: () => triggerRender()
    });
  });
}
