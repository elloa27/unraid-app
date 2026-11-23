/**
 * 실시간 미디어 및 인물 검색 화면
 */
import { api } from '../api.js';
import { state } from '../store.js';
import { ICONS } from '../icons.js';
import { escapeHtml, bindFallbackImages, attachLongPress } from '../utils.js';
import { renderTopBar, renderPosterCard, openQuickItemActionsSheet } from '../components/cards.js';
import { reportPluginError } from '../components/toast.js';

export function renderSearchView(appEl, view, { bindCommonActions, onOpenItem, onPlayItem }) {
  appEl.innerHTML = `
    ${renderTopBar({ title: '검색', showBack: true, showSearch: false, showRefresh: false })}
    <main>
      <div class="search-bar-wrap">
        <div class="search-input-box">
          <span class="search-leading-icon">${ICONS.search}</span>
          <input id="emby-search-input" type="search" placeholder="영화, 시리즈, 에피소드, 인물 검색..." value="${escapeHtml(view.query || '')}" autofocus />
          <button id="emby-search-clear" class="icon-btn ${view.query ? '' : 'hidden'}" aria-label="검색어 지우기">${ICONS.close}</button>
        </div>
      </div>
      <div id="search-results-container"></div>
    </main>
  `;

  bindCommonActions();
  renderSearchResultsOnly(view, { onOpenItem, onPlayItem });

  const input = document.getElementById('emby-search-input');
  const clearBtn = document.getElementById('emby-search-clear');

  input.addEventListener('input', () => {
    const q = input.value;
    clearBtn.classList.toggle('hidden', !q);
    if (state.searchTimer) clearTimeout(state.searchTimer);
    state.searchTimer = setTimeout(() => {
      runSearch(view, q, { onOpenItem, onPlayItem });
    }, 320);
  });

  clearBtn.addEventListener('click', () => {
    input.value = '';
    clearBtn.classList.add('hidden');
    input.focus();
    runSearch(view, '', { onOpenItem, onPlayItem });
  });
}

async function runSearch(view, query, handlers) {
  const trimmed = String(query || '').trim();
  view.query = query;
  if (!trimmed) {
    view.loading = false;
    view.error = '';
    view.results = [];
    renderSearchResultsOnly(view, handlers);
    return;
  }
  view.loading = true;
  view.error = '';
  renderSearchResultsOnly(view, handlers);
  try {
    const items = await api.searchItems(trimmed, 60);
    if (view.query.trim() !== trimmed) return;
    view.results = items;
    view.loading = false;
  } catch (err) {
    if (view.query.trim() !== trimmed) return;
    view.loading = false;
    view.error = err.message || '검색 중 오류가 발생했습니다.';
    reportPluginError('Search', view.error, err);
  }
  renderSearchResultsOnly(view, handlers);
}

function renderSearchResultsOnly(view, { onOpenItem, onPlayItem }) {
  const container = document.getElementById('search-results-container');
  if (!container) return;

  if (view.error) {
    container.innerHTML = `<div class="error-banner"><span>${escapeHtml(view.error)}</span></div>`;
    return;
  }

  if (!view.query.trim()) {
    container.innerHTML = `
      <div class="center-state">
        <p class="state-title">제목 · 인물로 미디어 검색</p>
        <p class="state-desc">검색어를 입력하면 영화·시리즈·에피소드·컬렉션·인물을 실시간으로 찾습니다.</p>
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

  const cols = state.prefs.gridCols || 3;
  container.innerHTML = `
    <div class="section-header" style="padding-top:10px;">
      <h2 class="section-title">검색 결과</h2>
      <span class="section-count">${view.results.length}건</span>
    </div>
    <div class="poster-grid cols-${cols}">${view.results.map((item) => renderPosterCard(item, { gridMode: true })).join('')}</div>
  `;

  bindFallbackImages(container);
  const itemMap = new Map(view.results.map((it) => [it.Id, it]));

  container.querySelectorAll('[data-open-item-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const item = itemMap.get(btn.dataset.openItemId);
      if (item) onOpenItem(item);
    });
  });

  attachLongPress(container, '[data-item-card]', (cardEl) => {
    const item = itemMap.get(cardEl.dataset.itemCard);
    if (!item) return;
    openQuickItemActionsSheet(item, {
      onPlay: (it) => onPlayItem(it, { resume: true }),
      onOpenDetail: (it) => onOpenItem(it),
      onMutated: () => renderSearchResultsOnly(view, { onOpenItem, onPlayItem })
    });
  });
}
