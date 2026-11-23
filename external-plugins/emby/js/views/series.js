/**
 * TV 시리즈 상세 화면 (시즌/에피소드 · 사용자 액션 · 출연진 · 비슷한 콘텐츠 · '...' 관리 메뉴)
 */
import { api } from '../api.js';
import { triggerRender } from '../store.js';
import { ICONS } from '../icons.js';
import {
  escapeHtml,
  formatEpisodeCode,
  formatRuntime,
  getProgressPercent,
  renderFallbackImage,
  bindFallbackImages,
  attachLongPress
} from '../utils.js';
import { renderTopBar, renderPosterCard, openQuickItemActionsSheet } from '../components/cards.js';
import { showToast, reportPluginError } from '../components/toast.js';
import { openAddToPlaylistSheet } from '../components/playlist-modal.js';
import { openItemMoreMenuSheet } from './metadata-modals.js';

export async function loadSeriesData(view, { silent = false } = {}) {
  if (!silent) {
    view.loading = true;
    view.error = '';
    triggerRender();
  }
  try {
    const [detail, seasons, similar] = await Promise.all([
      api.getItemDetail(view.seriesId),
      api.getSeasons(view.seriesId),
      api.getSimilarItems(view.seriesId, 14).catch(() => [])
    ]);
    view.series = detail || view.series;
    view.seasons = seasons;
    view.similar = similar;
    view.loading = false;

    if (!view.selectedSeasonId && seasons.length > 0) {
      const preferred = seasons.find(
        (s) => (s.IndexNumber == null || s.IndexNumber > 0) && !(s.UserData && s.UserData.Played)
      ) || seasons.find((s) => s.IndexNumber == null || s.IndexNumber > 0) || seasons[0];
      view.selectedSeasonId = preferred.Id;
    }
    await loadSeriesEpisodes(view, view.selectedSeasonId, silent);
  } catch (err) {
    view.loading = false;
    view.error = err.message || '시리즈 정보를 불러오지 못했습니다.';
    reportPluginError('SeriesLoad', view.error, err);
    triggerRender();
  }
}

export async function loadSeriesEpisodes(view, seasonId, silent = false) {
  view.selectedSeasonId = seasonId || '';
  if (!silent) {
    view.episodesLoading = true;
    triggerRender();
  }
  try {
    view.episodes = await api.getEpisodes(view.seriesId, view.selectedSeasonId);
    view.episodesLoading = false;
  } catch (err) {
    view.episodesLoading = false;
    view.error = err.message || '에피소드 목록을 불러오지 못했습니다.';
    reportPluginError('EpisodesLoad', view.error, err);
  }
  triggerRender();
}

export function renderSeriesView(appEl, view, {
  bindCommonActions,
  onOpenDetail,
  onOpenItem,
  onOpenPerson,
  onPlayItem,
  onRefreshHome,
  onItemDeleted
}) {
  const series = view.series || {};
  const backdropUrl = api.getBackdropUrl(series, 960);
  const posterUrl = api.getPosterUrl(series, 400);
  const ud = series.UserData || {};
  const isPlayed = Boolean(ud.Played);
  const isFav = Boolean(ud.IsFavorite);
  const people = Array.isArray(series.People) ? series.People.slice(0, 24) : [];
  const similar = Array.isArray(view.similar) ? view.similar : [];
  const genres = Array.isArray(series.Genres) ? series.Genres : [];

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
      <div class="center-state" style="min-height:200px;">
        <div class="spinner"></div>
        <p class="boot-text">에피소드 목록을 불러오는 중...</p>
      </div>
    `;
  } else if (view.episodes.length === 0) {
    episodesHtml = `
      <div class="center-state" style="min-height:160px;">
        <p class="state-desc">표시할 에피소드가 없습니다.</p>
      </div>
    `;
  } else {
    episodesHtml = `<div class="episode-list">${view.episodes.map((ep) => {
      const thumbUrl = api.getLandscapeImageUrl(ep, 420);
      const code = formatEpisodeCode(ep);
      const pct = getProgressPercent(ep);
      const played = Boolean(ep.UserData && ep.UserData.Played);
      const runtime = formatRuntime(ep.RunTimeTicks);

      return `
        <div class="episode-card" data-ep-card="${escapeHtml(ep.Id)}">
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
            <span class="btn-icon">${ICONS.play}</span>
          </button>
        </div>
      `;
    }).join('')}</div>`;
  }

  const peopleHtml = people.length > 0 ? `
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">출연진 및 제작진</h2>
        <span class="section-count">${people.length}명</span>
      </div>
      <div class="h-scroll">
        ${people.map((p) => `
          <button type="button" class="person-card" data-person-id="${escapeHtml(p.Id)}" data-person-name="${escapeHtml(p.Name || '')}">
            <div class="person-avatar">
              ${renderFallbackImage(api.getPersonImageUrl(p, 220), p.Name, p.Name)}
            </div>
            <p class="card-title">${escapeHtml(p.Name || '')}</p>
            <p class="card-sub">${escapeHtml(p.Role || p.Type || '')}</p>
          </button>
        `).join('')}
      </div>
    </section>
  ` : '';

  const similarHtml = similar.length > 0 ? `
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">비슷한 콘텐츠</h2>
        <span class="section-count">${similar.length}개</span>
      </div>
      <div class="h-scroll">
        ${similar.map((it) => renderPosterCard(it)).join('')}
      </div>
    </section>
  ` : '';

  appEl.innerHTML = `
    ${renderTopBar({ title: series.Name || '시리즈', showBack: true, showSearch: true, showRefresh: true, showMore: true })}
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

      <div class="detail-body" style="padding-bottom:8px;">
        <div class="quick-action-bar">
          <button type="button" class="action-pill ${isPlayed ? 'active' : ''}" id="series-toggle-played">
            <span class="mini-icon">${ICONS.checkCircle}</span>
            <span>${isPlayed ? '시청 완료' : '시청 표시'}</span>
          </button>
          <button type="button" class="action-pill ${isFav ? 'active-fav' : ''}" id="series-toggle-fav">
            <span class="mini-icon">${isFav ? ICONS.heartFilled : ICONS.heart}</span>
            <span>${isFav ? '즐겨찾기됨' : '즐겨찾기'}</span>
          </button>
          <button type="button" class="action-pill" id="series-add-playlist">
            <span class="mini-icon">${ICONS.playlistAdd}</span>
            <span>재생목록</span>
          </button>
        </div>

        ${genres.length > 0 ? `<div class="genre-chip-row">${genres.map((g) => `<span class="genre-tag">${escapeHtml(g)}</span>`).join('')}</div>` : ''}
        ${series.Overview ? `<p class="overview-text">${escapeHtml(series.Overview)}</p>` : ''}
      </div>

      ${seasonChips ? `<div class="sort-bar">${seasonChips}</div>` : ''}
      ${episodesHtml}
      ${peopleHtml}
      ${similarHtml}
    </main>
  `;

  bindFallbackImages(appEl);
  bindCommonActions();

  const moreBtn = appEl.querySelector('[data-action="open-more"]');
  if (moreBtn) {
    moreBtn.addEventListener('click', () => {
      openItemMoreMenuSheet(series, {
        onChanged: () => {
          loadSeriesData(view, { silent: true });
          onRefreshHome();
        },
        onDeleted: () => {
          onRefreshHome();
          onItemDeleted();
        }
      });
    });
  }

  document.getElementById('series-toggle-played').addEventListener('click', async () => {
    try {
      const next = !isPlayed;
      await api.setPlayed(series.Id, next);
      showToast(next ? '시리즈를 시청 완료로 표시했습니다.' : '미시청 상태로 변경했습니다.', 'success');
      await loadSeriesData(view, { silent: true });
      onRefreshHome();
    } catch (err) {
      reportPluginError('TogglePlayed', '시청 상태 변경에 실패했습니다.', err);
    }
  });

  document.getElementById('series-toggle-fav').addEventListener('click', async () => {
    try {
      const next = !isFav;
      await api.setFavorite(series.Id, next);
      showToast(next ? '즐겨찾기에 추가했습니다.' : '즐겨찾기에서 해제했습니다.', 'success');
      await loadSeriesData(view, { silent: true });
      onRefreshHome();
    } catch (err) {
      reportPluginError('ToggleFavorite', '즐겨찾기 변경에 실패했습니다.', err);
    }
  });

  document.getElementById('series-add-playlist').addEventListener('click', () => {
    openAddToPlaylistSheet(series, { onDone: onRefreshHome });
  });

  appEl.querySelectorAll('[data-season-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const sid = btn.dataset.seasonId;
      if (sid !== view.selectedSeasonId) loadSeriesEpisodes(view, sid);
    });
  });

  appEl.querySelectorAll('[data-open-ep-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const ep = view.episodes.find((e) => e.Id === btn.dataset.openEpId);
      if (ep) onOpenDetail(ep.Id, ep);
    });
  });

  appEl.querySelectorAll('[data-play-ep-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const ep = view.episodes.find((e) => e.Id === btn.dataset.playEpId);
      if (ep) onPlayItem(ep, { resume: true, knownSiblings: view.episodes });
    });
  });

  attachLongPress(appEl, '[data-ep-card]', (cardEl) => {
    const ep = view.episodes.find((e) => e.Id === cardEl.dataset.epCard);
    if (!ep) return;
    openQuickItemActionsSheet(ep, {
      onPlay: (it) => onPlayItem(it, { resume: true, knownSiblings: view.episodes }),
      onOpenDetail: (it) => onOpenDetail(it.Id, it),
      onMutated: () => {
        loadSeriesEpisodes(view, view.selectedSeasonId, true);
        onRefreshHome();
      }
    });
  });

  appEl.querySelectorAll('[data-person-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      onOpenPerson({ Id: btn.dataset.personId, Name: btn.dataset.personName });
    });
  });

  const similarMap = new Map(similar.map((s) => [s.Id, s]));
  appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const it = similarMap.get(btn.dataset.openItemId);
      if (it) onOpenItem(it);
    });
  });
}
