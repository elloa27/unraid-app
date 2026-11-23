/**
 * 재생목록 상세 화면 (항목 재생 · 항목 제거 · 이름 변경 · 재생목록 삭제)
 */
import { api } from '../api.js';
import { canDeleteItem, triggerRender } from '../store.js';
import { ICONS } from '../icons.js';
import {
  escapeHtml,
  formatEpisodeCode,
  formatRuntime,
  renderFallbackImage,
  bindFallbackImages
} from '../utils.js';
import { renderTopBar } from '../components/cards.js';
import { showToast, reportPluginError } from '../components/toast.js';
import { openRenamePlaylistModal } from '../components/playlist-modal.js';
import { openDeleteItemConfirm } from './metadata-modals.js';

export function createPlaylistViewState(playlist) {
  return {
    type: 'playlist',
    playlistId: playlist.Id,
    playlist,
    items: [],
    loading: true,
    error: ''
  };
}

export async function loadPlaylistData(view, silent = false) {
  if (!silent) {
    view.loading = true;
    view.error = '';
    triggerRender();
  }
  try {
    const [detail, items] = await Promise.all([
      api.getItemDetail(view.playlistId).catch(() => view.playlist),
      api.getPlaylistItems(view.playlistId)
    ]);
    if (detail) view.playlist = detail;
    view.items = items;
    view.loading = false;
  } catch (err) {
    view.loading = false;
    view.error = err.message || '재생목록 항목을 불러오지 못했습니다.';
    reportPluginError('PlaylistLoad', view.error, err);
  }
  triggerRender();
}

export function renderPlaylistView(appEl, view, {
  bindCommonActions,
  onOpenItem,
  onPlayItem,
  onRefreshHome,
  onDeleted
}) {
  const pl = view.playlist || {};
  const totalTicks = view.items.reduce((acc, it) => acc + Number(it.RunTimeTicks || 0), 0);
  const totalRuntime = formatRuntime(totalTicks);
  const deletable = canDeleteItem(pl);

  let bodyHtml = `
    <div class="playlist-header-card">
      <div>
        <p class="hero-eyebrow">재생목록</p>
        <h2 class="hero-title">${escapeHtml(pl.Name || '재생목록')}</h2>
        <p class="card-sub">${view.items.length}개 항목${totalRuntime ? ` · 총 ${totalRuntime}` : ''}</p>
      </div>
      <div class="quick-action-bar" style="margin-top:12px;">
        <button type="button" class="action-pill" id="pl-rename-btn">
          <span class="mini-icon">${ICONS.edit}</span>
          <span>이름 변경</span>
        </button>
        ${deletable ? `
          <button type="button" class="action-pill danger-pill" id="pl-delete-btn">
            <span class="mini-icon">${ICONS.trash}</span>
            <span>목록 삭제</span>
          </button>
        ` : ''}
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
        <p class="boot-text">재생목록을 불러오는 중...</p>
      </div>
    `;
  } else if (view.items.length === 0) {
    bodyHtml += `
      <div class="center-state">
        <p class="state-title">재생목록이 비어 있습니다</p>
        <p class="state-desc">영화나 에피소드의 상세 화면 또는 길게 누르기 메뉴에서 항목을 추가할 수 있습니다.</p>
      </div>
    `;
  } else {
    bodyHtml += `
      <div class="episode-list">
        ${view.items.map((item, idx) => {
          const thumbUrl = api.getLandscapeImageUrl(item, 420);
          const isEp = item.Type === 'Episode';
          const code = isEp ? [item.SeriesName, formatEpisodeCode(item)].filter(Boolean).join(' · ') : (item.ProductionYear ? `${item.ProductionYear}년` : '');
          const runtime = formatRuntime(item.RunTimeTicks);
          const entryId = item.PlaylistItemId || item.Id;

          return `
            <div class="episode-card">
              <button class="episode-thumb-btn" data-pl-open="${idx}">
                <div class="thumb-16-9">
                  ${renderFallbackImage(thumbUrl, item.Name, String(idx + 1))}
                </div>
              </button>
              <button class="episode-info" data-pl-open="${idx}">
                ${code ? `<div class="episode-code">${escapeHtml(code)}</div>` : ''}
                <p class="episode-name">${escapeHtml(item.Name || '제목 없음')}</p>
                <p class="episode-sub">${escapeHtml(runtime || '미디어')}</p>
              </button>
              <button class="episode-quick-play" data-pl-play="${idx}" aria-label="재생">
                <span class="btn-icon">${ICONS.play}</span>
              </button>
              <button class="icon-btn danger-icon-btn" data-pl-remove="${escapeHtml(entryId)}" data-pl-title="${escapeHtml(item.Name || '')}" aria-label="목록에서 제거">
                ${ICONS.trash}
              </button>
            </div>
          `;
        }).join('')}
      </div>
    `;
  }

  appEl.innerHTML = `
    ${renderTopBar({ title: pl.Name || '재생목록', showBack: true, showSearch: true, showRefresh: true })}
    <main>${bodyHtml}</main>
  `;

  bindFallbackImages(appEl);
  bindCommonActions();

  const renameBtn = document.getElementById('pl-rename-btn');
  if (renameBtn) {
    renameBtn.addEventListener('click', () => {
      openRenamePlaylistModal(pl, {
        onRenamed: (newName) => {
          view.playlist.Name = newName;
          triggerRender();
          onRefreshHome();
        }
      });
    });
  }

  const deleteBtn = document.getElementById('pl-delete-btn');
  if (deleteBtn) {
    deleteBtn.addEventListener('click', () => {
      openDeleteItemConfirm(pl, () => {
        onRefreshHome();
        onDeleted();
      });
    });
  }

  appEl.querySelectorAll('[data-pl-open]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const it = view.items[Number(btn.dataset.plOpen)];
      if (it) onOpenItem(it);
    });
  });

  appEl.querySelectorAll('[data-pl-play]').forEach((btn) => {
    btn.addEventListener('click', () => {
      const it = view.items[Number(btn.dataset.plPlay)];
      if (it) onPlayItem(it, { resume: true });
    });
  });

  appEl.querySelectorAll('[data-pl-remove]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const entryId = btn.dataset.plRemove;
      const title = btn.dataset.plTitle;
      btn.disabled = true;
      try {
        await api.removeFromPlaylist(view.playlistId, [entryId]);
        showToast(`'${title || '항목'}'을(를) 재생목록에서 제거했습니다.`, 'success');
        await loadPlaylistData(view, true);
        onRefreshHome();
      } catch (err) {
        btn.disabled = false;
        reportPluginError('RemovePlaylistItem', '재생목록에서 항목을 제거하지 못했습니다.', err);
      }
    });
  });
}
