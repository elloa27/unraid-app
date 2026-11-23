/**
 * 영화 · 에피소드 · 비디오 상세 화면
 * - 다중 버전(MediaSource) 및 오디오/자막 트랙 사전 선택
 * - 시청 완료 / 즐겨찾기 / 재생목록 추가 / 미디어 정보 빠른 버튼
 * - 우측 상단 '...' 메타데이터 편집/새로고침/식별/이미지 관리/삭제 메뉴
 * - 챕터 목록 (탭 시 해당 시간부터 즉시 libmpv 재생)
 * - 출연진 및 제작진 (인물 탭 시 참여 작품 목록 이동)
 * - 비슷한 콘텐츠 (/Items/{Id}/Similar)
 */
import { api } from '../api.js';
import { state, triggerRender } from '../store.js';
import { ICONS } from '../icons.js';
import {
  TICKS_PER_SECOND,
  escapeHtml,
  formatEpisodeCode,
  formatRuntime,
  formatTimestamp,
  formatFileSize,
  matchesPreferredLang,
  renderFallbackImage,
  bindFallbackImages
} from '../utils.js';
import { renderTopBar, renderPosterCard } from '../components/cards.js';
import { showToast, reportPluginError } from '../components/toast.js';
import { openAddToPlaylistSheet } from '../components/playlist-modal.js';
import { openItemMoreMenuSheet, openMediaInfoModal } from './metadata-modals.js';

export function createDetailViewState(itemId, previewItem = null) {
  return {
    type: 'detail',
    itemId,
    item: previewItem,
    siblingEpisodes: [],
    similar: [],
    selectedMediaSourceId: '',
    selectedAudioIndex: null,
    selectedSubValue: 'auto', // 'auto' | 'none' | String(Index)
    loading: true,
    error: ''
  };
}

function initDefaultTrackSelections(view, item) {
  const sources = Array.isArray(item && item.MediaSources) ? item.MediaSources : [];
  if (sources.length === 0) return;

  if (!view.selectedMediaSourceId || !sources.some((s) => s.Id === view.selectedMediaSourceId)) {
    view.selectedMediaSourceId = sources[0].Id;
  }

  const ms = sources.find((s) => s.Id === view.selectedMediaSourceId) || sources[0];
  const streams = Array.isArray(ms.MediaStreams) ? ms.MediaStreams : [];
  const audioStreams = streams.filter((s) => s.Type === 'Audio');
  const subStreams = streams.filter((s) => s.Type === 'Subtitle');

  // 기본 오디오 트랙 자동 추천
  if (view.selectedAudioIndex === null && audioStreams.length > 0) {
    const prefAudio = audioStreams.find((a) => matchesPreferredLang(a, state.prefs.preferredAudioLang))
      || audioStreams.find((a) => a.IsDefault)
      || audioStreams[0];
    view.selectedAudioIndex = prefAudio ? prefAudio.Index : null;
  }

  // 기본 자막 트랙 자동 추천
  if (view.selectedSubValue === 'auto' && subStreams.length > 0) {
    if (state.prefs.preferredSubLang === 'none') {
      view.selectedSubValue = 'none';
    } else {
      const prefSub = subStreams.find((s) => matchesPreferredLang(s, state.prefs.preferredSubLang))
        || subStreams.find((s) => s.IsDefault)
        || null;
      if (prefSub) view.selectedSubValue = String(prefSub.Index);
    }
  }
}

export async function loadDetailData(view, silent = false) {
  if (!silent) {
    view.loading = true;
    view.error = '';
    triggerRender();
  }
  try {
    const [detail, similar] = await Promise.all([
      api.getItemDetail(view.itemId),
      api.getSimilarItems(view.itemId, 14).catch(() => [])
    ]);
    view.item = detail;
    view.similar = similar;
    view.loading = false;
    initDefaultTrackSelections(view, detail);

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
  triggerRender();
}

function buildVersionOptionLabel(ms, idx) {
  const streams = Array.isArray(ms.MediaStreams) ? ms.MediaStreams : [];
  const v = streams.find((s) => s.Type === 'Video');
  const parts = [
    ms.Name || `버전 #${idx + 1}`,
    v && v.Height ? `${v.Height}p` : '',
    v && v.Codec ? v.Codec.toUpperCase() : '',
    ms.Size ? formatFileSize(ms.Size) : ''
  ].filter(Boolean);
  return parts.join(' · ');
}

function buildAudioOptionLabel(a) {
  const title = a.DisplayTitle || a.Title || a.Language || `오디오 #${a.Index}`;
  const codec = [a.Codec && a.Codec.toUpperCase(), a.ChannelLayout || (a.Channels ? `${a.Channels}ch` : '')].filter(Boolean).join(' ');
  return codec && !title.toUpperCase().includes(a.Codec ? a.Codec.toUpperCase() : '___')
    ? `${title} (${codec})`
    : title;
}

function buildSubOptionLabel(s) {
  const title = s.DisplayTitle || s.Title || s.Language || `자막 #${s.Index}`;
  const tag = [s.Codec && s.Codec.toUpperCase(), s.IsExternal ? '외부' : '내장'].filter(Boolean).join(' · ');
  return `${title} [${tag}]`;
}

export function renderDetailView(appEl, view, {
  bindCommonActions,
  onPlayItem,
  onOpenDetail,
  onOpenSeries,
  onOpenPerson,
  onOpenItem,
  onRefreshHome,
  onItemDeleted
}) {
  const item = view.item || {};
  const backdropUrl = api.getBackdropUrl(item, 960);
  const posterUrl = api.getPosterUrl(item, 400);
  const logoUrl = api.getLogoUrl(item, 440);
  const ud = item.UserData || {};
  const isPlayed = Boolean(ud.Played);
  const isFav = Boolean(ud.IsFavorite);
  const resumeSec = Math.floor((ud.PlaybackPositionTicks || 0) / TICKS_PER_SECOND);
  const hasResume = resumeSec >= 5;

  const sources = Array.isArray(item.MediaSources) ? item.MediaSources : [];
  const selectedMs = (view.selectedMediaSourceId && sources.find((s) => s.Id === view.selectedMediaSourceId))
    || sources[0]
    || null;
  const streams = (selectedMs && Array.isArray(selectedMs.MediaStreams))
    ? selectedMs.MediaStreams
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
  const eyebrowText = isEpisode
    ? [item.SeriesName, formatEpisodeCode(item)].filter(Boolean).join(' · ')
    : (item.Type === 'Movie' ? '영화' : '비디오');

  let prevEp = null;
  let nextEp = null;
  if (isEpisode && Array.isArray(view.siblingEpisodes) && view.siblingEpisodes.length > 0) {
    const idx = view.siblingEpisodes.findIndex((e) => e.Id === item.Id);
    if (idx > 0) prevEp = view.siblingEpisodes[idx - 1];
    if (idx >= 0 && idx < view.siblingEpisodes.length - 1) nextEp = view.siblingEpisodes[idx + 1];
  }

  const genres = Array.isArray(item.Genres) ? item.Genres : [];
  const chapters = Array.isArray(item.Chapters) ? item.Chapters.filter((c) => c && typeof c.StartPositionTicks === 'number') : [];
  const people = Array.isArray(item.People) ? item.People.slice(0, 24) : [];
  const similar = Array.isArray(view.similar) ? view.similar : [];

  const getPlaybackSelection = (resumeFlag, startOverride = null) => {
    let subtitleSelection = 'auto';
    if (view.selectedSubValue === 'none') subtitleSelection = 'none';
    else if (view.selectedSubValue !== 'auto') {
      const parsed = parseInt(view.selectedSubValue, 10);
      if (!Number.isNaN(parsed)) subtitleSelection = parsed;
    }
    return {
      resume: resumeFlag,
      startPositionSecOverride: startOverride,
      mediaSourceId: selectedMs ? selectedMs.Id : '',
      audioStreamIndex: view.selectedAudioIndex,
      subtitleSelection,
      knownSiblings: view.siblingEpisodes
    };
  };

  // 다중 버전 / 오디오 / 자막 사전 선택 카드
  const trackSelectorHtml = (sources.length > 0 || audioStreams.length > 0 || subStreams.length > 0) ? `
    <div class="stream-card">
      <h3 class="stream-heading">버전 및 트랙 선택</h3>
      ${sources.length > 1 ? `
        <div class="selector-row">
          <label class="selector-label" for="sel-media-version">파일 버전 (${sources.length})</label>
          <select id="sel-media-version" class="select-input">
            ${sources.map((ms, idx) => `
              <option value="${escapeHtml(ms.Id)}" ${selectedMs && ms.Id === selectedMs.Id ? 'selected' : ''}>
                ${escapeHtml(buildVersionOptionLabel(ms, idx))}
              </option>
            `).join('')}
          </select>
        </div>
      ` : ''}

      ${audioStreams.length > 0 ? `
        <div class="selector-row">
          <label class="selector-label" for="sel-audio-track">오디오 (${audioStreams.length})</label>
          <select id="sel-audio-track" class="select-input">
            ${audioStreams.map((a) => `
              <option value="${a.Index}" ${view.selectedAudioIndex === a.Index ? 'selected' : ''}>
                ${escapeHtml(buildAudioOptionLabel(a))}
              </option>
            `).join('')}
          </select>
        </div>
      ` : ''}

      ${subStreams.length > 0 ? `
        <div class="selector-row">
          <label class="selector-label" for="sel-sub-track">자막 (${subStreams.length})</label>
          <select id="sel-sub-track" class="select-input">
            <option value="auto" ${view.selectedSubValue === 'auto' ? 'selected' : ''}>자동 (선호 언어 우선)</option>
            <option value="none" ${view.selectedSubValue === 'none' ? 'selected' : ''}>자막 끄기</option>
            ${subStreams.map((s) => `
              <option value="${s.Index}" ${String(view.selectedSubValue) === String(s.Index) ? 'selected' : ''}>
                ${escapeHtml(buildSubOptionLabel(s))}
              </option>
            `).join('')}
          </select>
        </div>
      ` : ''}
      <p class="selector-hint">선택한 버전과 외부 자막이 우선 적용되며, 내장 트랙은 플레이어 안에서도 즉시 변경할 수 있습니다.</p>
    </div>
  ` : '';

  // 챕터 목록
  const chaptersHtml = chapters.length > 0 ? `
    <section class="section">
      <div class="section-header">
        <h2 class="section-title">챕터</h2>
        <span class="section-count">${chapters.length}개</span>
      </div>
      <div class="h-scroll">
        ${chapters.map((ch, idx) => {
          const sec = Math.floor(ch.StartPositionTicks / TICKS_PER_SECOND);
          const chImg = api.getChapterImageUrl(item.Id, idx, ch.ImageTag, 360);
          return `
            <button type="button" class="chapter-card" data-chapter-sec="${sec}">
              <div class="thumb-16-9">
                ${renderFallbackImage(chImg, ch.Name || `챕터 ${idx + 1}`, String(idx + 1))}
                <span class="chapter-time-badge">${formatTimestamp(sec)}</span>
              </div>
              <div class="chapter-meta">
                <p class="card-title">${escapeHtml(ch.Name || `챕터 ${idx + 1}`)}</p>
              </div>
            </button>
          `;
        }).join('')}
      </div>
    </section>
  ` : '';

  // 출연진 및 제작진
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

  // 비슷한 콘텐츠
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
    ${renderTopBar({ title: item.Name || '상세 정보', showBack: true, showSearch: true, showRefresh: true, showMore: true })}
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
            ${isEpisode && item.SeriesId
              ? `<button type="button" class="hero-eyebrow clickable-eyebrow" id="btn-goto-series">${escapeHtml(eyebrowText)} ›</button>`
              : `<p class="hero-eyebrow">${escapeHtml(eyebrowText)}</p>`
            }
            ${logoUrl ? `<img class="hero-logo" src="${escapeHtml(logoUrl)}" alt="${escapeHtml(item.Name || '')}" onerror="this.style.display='none'" />` : ''}
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
                <span class="btn-icon">${ICONS.play}</span>
                <span>이어보기 (${formatTimestamp(resumeSec)}부터 · libmpv)</span>
              </button>
              <button class="secondary-btn" id="btn-play-start">처음부터 재생 (Direct Play)</button>
            ` : `
              <button class="primary-btn" id="btn-play-start">
                <span class="btn-icon">${ICONS.play}</span>
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

          <div class="quick-action-bar">
            <button type="button" class="action-pill ${isPlayed ? 'active' : ''}" id="detail-toggle-played">
              <span class="mini-icon">${ICONS.checkCircle}</span>
              <span>${isPlayed ? '시청 완료' : '시청 표시'}</span>
            </button>
            <button type="button" class="action-pill ${isFav ? 'active-fav' : ''}" id="detail-toggle-fav">
              <span class="mini-icon">${isFav ? ICONS.heartFilled : ICONS.heart}</span>
              <span>${isFav ? '즐겨찾기됨' : '즐겨찾기'}</span>
            </button>
            <button type="button" class="action-pill" id="detail-add-playlist">
              <span class="mini-icon">${ICONS.playlistAdd}</span>
              <span>재생목록</span>
            </button>
            <button type="button" class="action-pill" id="detail-media-info">
              <span class="mini-icon">${ICONS.info}</span>
              <span>미디어 정보</span>
            </button>
          </div>

          ${genres.length > 0 ? `<div class="genre-chip-row">${genres.map((g) => `<span class="genre-tag">${escapeHtml(g)}</span>`).join('')}</div>` : ''}
          ${item.Overview ? `<p class="overview-text">${escapeHtml(item.Overview)}</p>` : ''}

          ${trackSelectorHtml}
        `}
      </div>

      ${chaptersHtml}
      ${peopleHtml}
      ${similarHtml}
    </main>
  `;

  bindFallbackImages(appEl);
  bindCommonActions();

  const moreBtn = appEl.querySelector('[data-action="open-more"]');
  if (moreBtn) {
    moreBtn.addEventListener('click', () => {
      openItemMoreMenuSheet(item, {
        selectedMediaSourceId: selectedMs ? selectedMs.Id : '',
        onChanged: () => {
          loadDetailData(view, true);
          onRefreshHome();
        },
        onDeleted: () => {
          onRefreshHome();
          onItemDeleted();
        }
      });
    });
  }

  const gotoSeriesBtn = document.getElementById('btn-goto-series');
  if (gotoSeriesBtn && item.SeriesId) {
    gotoSeriesBtn.addEventListener('click', () => {
      onOpenSeries({ Id: item.SeriesId, Name: item.SeriesName || '시리즈', Type: 'Series' });
    });
  }

  const resumeBtn = document.getElementById('btn-play-resume');
  if (resumeBtn) {
    resumeBtn.addEventListener('click', () => onPlayItem(item, getPlaybackSelection(true)));
  }

  const startBtn = document.getElementById('btn-play-start');
  if (startBtn) {
    startBtn.addEventListener('click', () => onPlayItem(item, getPlaybackSelection(false)));
  }

  const prevBtn = document.getElementById('btn-prev-ep');
  if (prevBtn && prevEp) {
    prevBtn.addEventListener('click', () => onOpenDetail(prevEp.Id, prevEp, true));
  }
  const nextBtn = document.getElementById('btn-next-ep');
  if (nextBtn && nextEp) {
    nextBtn.addEventListener('click', () => onOpenDetail(nextEp.Id, nextEp, true));
  }

  // 빠른 상호작용 버튼
  const playedBtn = document.getElementById('detail-toggle-played');
  if (playedBtn) {
    playedBtn.addEventListener('click', async () => {
      try {
        const next = !isPlayed;
        await api.setPlayed(item.Id, next);
        showToast(next ? '시청 완료로 표시했습니다.' : '미시청 상태로 변경했습니다.', 'success');
        await loadDetailData(view, true);
        onRefreshHome();
      } catch (err) {
        reportPluginError('TogglePlayed', '시청 상태 변경에 실패했습니다.', err);
      }
    });
  }

  const favBtn = document.getElementById('detail-toggle-fav');
  if (favBtn) {
    favBtn.addEventListener('click', async () => {
      try {
        const next = !isFav;
        await api.setFavorite(item.Id, next);
        showToast(next ? '즐겨찾기에 추가했습니다.' : '즐겨찾기에서 해제했습니다.', 'success');
        await loadDetailData(view, true);
        onRefreshHome();
      } catch (err) {
        reportPluginError('ToggleFavorite', '즐겨찾기 변경에 실패했습니다.', err);
      }
    });
  }

  const plBtn = document.getElementById('detail-add-playlist');
  if (plBtn) {
    plBtn.addEventListener('click', () => {
      openAddToPlaylistSheet(item, { onDone: onRefreshHome });
    });
  }

  const infoBtn = document.getElementById('detail-media-info');
  if (infoBtn) {
    infoBtn.addEventListener('click', () => {
      openMediaInfoModal(item, selectedMs ? selectedMs.Id : '');
    });
  }

  // 버전 / 오디오 / 자막 셀렉터 바인딩
  const verSel = document.getElementById('sel-media-version');
  if (verSel) {
    verSel.addEventListener('change', () => {
      view.selectedMediaSourceId = verSel.value;
      view.selectedAudioIndex = null;
      view.selectedSubValue = 'auto';
      initDefaultTrackSelections(view, item);
      triggerRender();
    });
  }

  const audioSel = document.getElementById('sel-audio-track');
  if (audioSel) {
    audioSel.addEventListener('change', () => {
      const parsed = parseInt(audioSel.value, 10);
      view.selectedAudioIndex = Number.isNaN(parsed) ? null : parsed;
    });
  }

  const subSel = document.getElementById('sel-sub-track');
  if (subSel) {
    subSel.addEventListener('change', () => {
      view.selectedSubValue = subSel.value;
    });
  }

  // 챕터 클릭 -> 해당 시간부터 즉시 재생
  appEl.querySelectorAll('[data-chapter-sec]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const sec = Number(btn.dataset.chapterSec || 0);
      onPlayItem(item, getPlaybackSelection(false, sec));
    });
  });

  // 인물 클릭 -> 참여 작품 목록
  appEl.querySelectorAll('[data-person-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      onOpenPerson({ Id: btn.dataset.personId, Name: btn.dataset.personName });
    });
  });

  // 비슷한 콘텐츠 클릭
  const similarMap = new Map(similar.map((s) => [s.Id, s]));
  appEl.querySelectorAll('[data-open-item-id]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const it = similarMap.get(btn.dataset.openItemId);
      if (it) onOpenItem(it);
    });
  });
}
