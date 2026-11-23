/**
 * 인물(출연진/제작진) 상세 및 참여 작품 목록 화면
 */
import { api } from '../api.js';
import { state, triggerRender } from '../store.js';
import { escapeHtml, renderFallbackImage, bindFallbackImages, attachLongPress } from '../utils.js';
import { renderTopBar, renderPosterCard, openQuickItemActionsSheet } from '../components/cards.js';
import { reportPluginError } from '../components/toast.js';

const PAGE_LIMIT = 48;

export function createPersonViewState(person) {
  return {
    type: 'person',
    personId: person.Id,
    person,
    items: [],
    totalRecordCount: 0,
    loading: true,
    loadingMore: false,
    error: ''
  };
}

export async function loadPersonData(view, { reset = true } = {}) {
  if (reset) {
    view.loading = true;
    view.error = '';
    triggerRender();
  } else {
    if (view.loadingMore || view.items.length >= view.totalRecordCount) return;
    view.loadingMore = true;
    triggerRender();
  }

  try {
    const startIndex = reset ? 0 : view.items.length;
    const tasks = [api.getPersonItems(view.personId, startIndex, PAGE_LIMIT)];
    if (reset) {
      tasks.push(api.getPersonDetail(view.personId).catch(() => null));
    }
    const [worksRes, personDetail] = await Promise.all(tasks);
    if (personDetail) view.person = { ...view.person, ...personDetail };
    view.items = reset ? worksRes.items : [...view.items, ...worksRes.items];
    view.totalRecordCount = worksRes.totalRecordCount;
    view.loading = false;
    view.loadingMore = false;
  } catch (err) {
    view.loading = false;
    view.loadingMore = false;
    view.error = err.message || '인물 정보를 불러오지 못했습니다.';
    reportPluginError('PersonLoad', view.error, err);
  }
  triggerRender();
}

export function renderPersonView(appEl, view, { bindCommonActions, onOpenItem, onPlayItem }) {
  const person = view.person || {};
  const photoUrl = api.getPersonImageUrl(person, 360);
  const cols = state.prefs.gridCols || 3;
  const hasMore = view.items.length < view.totalRecordCount;

  let bodyHtml = `
    <section class="person-hero">
      <div class="person-hero-avatar">
        ${renderFallbackImage(photoUrl, person.Name || '', person.Name || '?')}
      </div>
      <div class="person-hero-info">
        <p class="hero-eyebrow">인물 정보</p>
        <h2 class="hero-title">${escapeHtml(person.Name || '이름 없음')}</h2>
        ${view.totalRecordCount > 0 ? `<p class="card-sub">라이브러리 내 참여 작품 ${view.totalRecordCount}편</p>` : ''}
      </div>
    </section>
  `;

  if (person.Overview) {
    bodyHtml += `<div class="detail-body" style="padding-top:0;padding-bottom:8px;"><p class="overview-text">${escapeHtml(person.Overview)}</p></div>`;
  }

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
        <p class="boot-text">참여 작품을 불러오는 중...</p>
      </div>
    `;
  } else if (view.items.length === 0) {
    bodyHtml += `
      <div class="center-state">
        <p class="state-title">참여 작품이 없습니다</p>
        <p class="state-desc">현재 라이브러리에서 이 인물이 참여한 다른 작품을 찾지 못했습니다.</p>
      </div>
    `;
  } else {
    bodyHtml += `
      <div class="section-header" style="padding-top:8px;">
        <h2 class="section-title">라이브러리 내 참여 작품</h2>
        <span class="section-count">${view.totalRecordCount}편</span>
      </div>
      <div class="poster-grid cols-${cols}">
        ${view.items.map((item) => renderPosterCard(item, { gridMode: true })).join('')}
      </div>
      ${hasMore ? `
        <div class="load-more-wrap">
          <button type="button" class="secondary-btn load-more-btn" id="person-load-more" ${view.loadingMore ? 'disabled' : ''}>
            ${view.loadingMore ? '불러오는 중...' : `더 보기 (${view.items.length} / ${view.totalRecordCount})`}
          </button>
        </div>
      ` : ''}
    `;
  }

  appEl.innerHTML = `
    ${renderTopBar({ title: person.Name || '인물', showBack: true, showSearch: true, showRefresh: true })}
    <main>${bodyHtml}</main>
  `;

  bindFallbackImages(appEl);
  bindCommonActions();

  const moreBtn = document.getElementById('person-load-more');
  if (moreBtn) {
    moreBtn.addEventListener('click', () => loadPersonData(view, { reset: false }));
  }

  const itemMap = new Map(view.items.map((it) => [it.Id, it]));
  appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const it = itemMap.get(btn.dataset.openItemId);
      if (it) onOpenItem(it);
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
