/**
 * 공용 상단 바, 포스터 카드, 16:9 가로 카드, 길게 누르기 빠른 액션 시트
 */
import { api } from '../api.js';
import { state } from '../store.js';
import { ICONS } from '../icons.js';
import {
  escapeHtml,
  formatEpisodeCode,
  getProgressPercent,
  getItemTypeLabel,
  renderFallbackImage
} from '../utils.js';
import { openSheet } from './modal.js';
import { showToast, reportPluginError } from './toast.js';
import { openAddToPlaylistSheet } from './playlist-modal.js';

export function renderTopBar({
  title,
  showBack = false,
  showSearch = true,
  showRefresh = false,
  showMore = false,
  showSettings = true
}) {
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
          ${showMore ? `<button class="icon-btn" data-action="open-more" aria-label="더 보기 메뉴">${ICONS.more}</button>` : ''}
          ${showSettings ? `<button class="icon-btn" data-action="open-settings" aria-label="설정">${ICONS.settings}</button>` : ''}
        </div>
      </div>
    </header>
  `;
}

export function renderPosterCard(item, { gridMode = false } = {}) {
  const posterUrl = item.Type === 'Person'
    ? api.getPersonImageUrl(item, 360)
    : api.getPosterUrl(item, 400);
  const ud = item.UserData || {};
  const isPlayed = Boolean(ud.Played);
  const isFav = Boolean(ud.IsFavorite);
  const unplayedCount = ud.UnplayedItemCount;

  let subText = '';
  if (item.Type === 'Episode') {
    subText = [item.SeriesName, formatEpisodeCode(item)].filter(Boolean).join(' · ');
  } else if (item.Type === 'BoxSet' || item.Type === 'Playlist') {
    const cnt = item.ChildCount || item.RecursiveItemCount;
    subText = cnt ? `${cnt}개 항목` : getItemTypeLabel(item.Type);
  } else if (item.Type === 'Person') {
    subText = '인물';
  } else {
    subText = [
      item.ProductionYear,
      item.CommunityRating ? `★ ${Number(item.CommunityRating).toFixed(1)}` : getItemTypeLabel(item.Type)
    ].filter(Boolean).join(' · ');
  }

  let badgeHtml = '';
  if (isPlayed) {
    badgeHtml = '<span class="poster-badge-top played">✓</span>';
  } else if (item.Type === 'Series' && unplayedCount > 0) {
    badgeHtml = `<span class="poster-badge-top">${unplayedCount}</span>`;
  }

  const favHtml = isFav
    ? `<span class="poster-fav-badge" aria-label="즐겨찾기">${ICONS.heartFilled}</span>`
    : '';

  return `
    <button class="poster-card ${gridMode ? 'grid-card' : ''}" data-open-item-id="${escapeHtml(item.Id)}" data-item-card="${escapeHtml(item.Id)}">
      <div class="poster-2-3">
        ${renderFallbackImage(posterUrl, item.Name, item.Name)}
        ${favHtml}
        ${badgeHtml}
      </div>
      <div class="poster-meta">
        <p class="card-title">${escapeHtml(item.Name || '제목 없음')}</p>
        <p class="card-sub">${subText ? escapeHtml(subText) : '&nbsp;'}</p>
      </div>
    </button>
  `;
}

export function renderLandscapeCard(item, { showPlayBtn = true, showProgress = true, eyebrowLabel = '' } = {}) {
  const thumbUrl = api.getLandscapeImageUrl(item, 540);
  const pct = getProgressPercent(item);
  const isEp = item.Type === 'Episode';
  const title = isEp ? (item.SeriesName || item.Name) : item.Name;
  const sub = isEp
    ? [formatEpisodeCode(item), item.Name].filter(Boolean).join(' · ')
    : (pct > 0 ? `${Math.round(pct)}% 시청` : (item.ProductionYear ? `${item.ProductionYear}년` : ''));

  return `
    <div class="resume-card" data-open-item-id="${escapeHtml(item.Id)}" data-item-card="${escapeHtml(item.Id)}">
      <div class="thumb-16-9">
        ${renderFallbackImage(thumbUrl, title, title)}
        ${eyebrowLabel ? `<span class="thumb-pill-tl">${escapeHtml(eyebrowLabel)}</span>` : ''}
        ${showPlayBtn ? `
          <button class="resume-play-badge" data-quick-play-id="${escapeHtml(item.Id)}" aria-label="바로 재생">
            ${ICONS.play}
          </button>
        ` : ''}
        ${showProgress && pct > 0 ? `<div class="progress-track"><div class="progress-fill" style="width:${pct.toFixed(1)}%"></div></div>` : ''}
      </div>
      <div class="resume-meta">
        <p class="card-title">${escapeHtml(title || '제목 없음')}</p>
        <p class="card-sub">${escapeHtml(sub || '')}</p>
      </div>
    </div>
  `;
}

/**
 * 카드 길게 누르기 시 표시되는 빠른 상호작용 바텀시트
 */
export function openQuickItemActionsSheet(item, {
  onPlay = null,
  onOpenDetail = null,
  onMutated = null
} = {}) {
  if (!item || !item.Id || item.Type === 'Person') return;

  const ud = item.UserData || {};
  const played = Boolean(ud.Played);
  const isFav = Boolean(ud.IsFavorite);
  const isPlayable = ['Movie', 'Episode', 'Video', 'MusicVideo'].includes(item.Type);

  const subTitle = item.Type === 'Episode'
    ? [item.SeriesName, formatEpisodeCode(item)].filter(Boolean).join(' · ')
    : getItemTypeLabel(item.Type);

  openSheet({
    title: item.Name || '항목 메뉴',
    subtitle: subTitle,
    bodyHtml: `
      <div class="sheet-menu-list">
        ${isPlayable && onPlay ? `
          <button type="button" class="sheet-menu-item" data-act="play">
            <span class="sheet-menu-icon accent">${ICONS.play}</span>
            <span>바로 재생</span>
          </button>
        ` : ''}
        ${onOpenDetail ? `
          <button type="button" class="sheet-menu-item" data-act="detail">
            <span class="sheet-menu-icon">${ICONS.info}</span>
            <span>상세 화면 열기</span>
          </button>
        ` : ''}
        <button type="button" class="sheet-menu-item" data-act="played">
          <span class="sheet-menu-icon ${played ? 'accent' : ''}">${ICONS.checkCircle}</span>
          <span>${played ? '미시청으로 표시' : '시청 완료로 표시'}</span>
        </button>
        <button type="button" class="sheet-menu-item" data-act="favorite">
          <span class="sheet-menu-icon ${isFav ? 'danger' : ''}">${isFav ? ICONS.heartFilled : ICONS.heart}</span>
          <span>${isFav ? '즐겨찾기 해제' : '즐겨찾기에 추가'}</span>
        </button>
        ${item.Type !== 'Playlist' ? `
          <button type="button" class="sheet-menu-item" data-act="playlist">
            <span class="sheet-menu-icon">${ICONS.playlistAdd}</span>
            <span>재생목록에 추가</span>
          </button>
        ` : ''}
      </div>
    `,
    onMount: (el, close) => {
      const bind = (act, fn) => {
        const btn = el.querySelector(`[data-act="${act}"]`);
        if (btn) btn.addEventListener('click', fn);
      };

      bind('play', () => {
        close();
        if (onPlay) onPlay(item);
      });

      bind('detail', () => {
        close();
        if (onOpenDetail) onOpenDetail(item);
      });

      bind('played', async () => {
        close();
        try {
          const nextPlayed = !played;
          await api.setPlayed(item.Id, nextPlayed);
          item.UserData = { ...(item.UserData || {}), Played: nextPlayed };
          showToast(nextPlayed ? '시청 완료로 표시했습니다.' : '미시청 상태로 변경했습니다.', 'success');
          if (onMutated) onMutated();
        } catch (err) {
          reportPluginError('TogglePlayed', '시청 상태 변경에 실패했습니다.', err);
        }
      });

      bind('favorite', async () => {
        close();
        try {
          const nextFav = !isFav;
          await api.setFavorite(item.Id, nextFav);
          item.UserData = { ...(item.UserData || {}), IsFavorite: nextFav };
          showToast(nextFav ? '즐겨찾기에 추가했습니다.' : '즐겨찾기에서 제거했습니다.', 'success');
          if (onMutated) onMutated();
        } catch (err) {
          reportPluginError('ToggleFavorite', '즐겨찾기 변경에 실패했습니다.', err);
        }
      });

      bind('playlist', () => {
        close();
        openAddToPlaylistSheet(item, { onDone: onMutated });
      });
    }
  });
}
