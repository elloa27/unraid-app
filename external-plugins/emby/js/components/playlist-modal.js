/**
 * 재생목록 추가 / 생성 / 이름 변경 바텀시트 및 모달
 */
import { api } from '../api.js';
import { ICONS } from '../icons.js';
import { escapeHtml } from '../utils.js';
import { openSheet, openModal } from './modal.js';
import { showToast, reportPluginError } from './toast.js';

/**
 * 항목을 기존 재생목록에 추가하거나 새 재생목록을 만들어 추가
 */
export function openAddToPlaylistSheet(item, { onDone = null } = {}) {
  if (!item || !item.Id) return;

  openSheet({
    title: '재생목록에 추가',
    subtitle: item.Name || '',
    bodyHtml: `
      <form id="new-playlist-inline-form" class="inline-create-row">
        <input id="new-playlist-name" class="text-input" type="text" placeholder="새 재생목록 이름 입력..." required />
        <button type="submit" class="primary-btn compact-btn">생성</button>
      </form>
      <div id="playlist-picker-list" class="sheet-menu-list">
        <div class="center-state compact"><div class="spinner"></div><p class="boot-text">재생목록 불러오는 중...</p></div>
      </div>
    `,
    onMount: async (el, close) => {
      const listEl = el.querySelector('#playlist-picker-list');
      const form = el.querySelector('#new-playlist-inline-form');
      const input = el.querySelector('#new-playlist-name');

      const loadLists = async () => {
        try {
          const playlists = await api.getPlaylists(50);
          if (playlists.length === 0) {
            listEl.innerHTML = '<p class="empty-inline">생성된 재생목록이 없습니다. 위에서 새로 만들어 보세요.</p>';
            return;
          }
          listEl.innerHTML = playlists.map((pl) => `
            <button type="button" class="sheet-menu-item" data-pl-id="${escapeHtml(pl.Id)}" data-pl-name="${escapeHtml(pl.Name || '')}">
              <span class="sheet-menu-icon accent">${ICONS.list}</span>
              <span class="sheet-menu-label-col">
                <span>${escapeHtml(pl.Name || '이름 없음')}</span>
                <span class="sheet-menu-sub">${pl.ChildCount ? `${pl.ChildCount}개 항목` : '재생목록'}</span>
              </span>
              <span class="sheet-menu-trailing">${ICONS.plus}</span>
            </button>
          `).join('');

          listEl.querySelectorAll('[data-pl-id]').forEach((btn) => {
            btn.addEventListener('click', async () => {
              const plId = btn.dataset.plId;
              const plName = btn.dataset.plName;
              btn.disabled = true;
              try {
                await api.addToPlaylist(plId, [item.Id]);
                showToast(`'${plName}' 재생목록에 추가했습니다.`, 'success');
                close();
                if (onDone) onDone();
              } catch (err) {
                btn.disabled = false;
                reportPluginError('AddToPlaylist', '재생목록에 추가하지 못했습니다.', err);
              }
            });
          });
        } catch (err) {
          listEl.innerHTML = '<p class="empty-inline error">재생목록을 불러오지 못했습니다.</p>';
          reportPluginError('LoadPlaylists', '재생목록 목록 조회에 실패했습니다.', err);
        }
      };

      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = input.value.trim();
        if (!name) return;
        const submitBtn = form.querySelector('button[type="submit"]');
        submitBtn.disabled = true;
        try {
          await api.createPlaylist(name, [item.Id]);
          showToast(`'${name}' 재생목록을 만들고 항목을 추가했습니다.`, 'success');
          close();
          if (onDone) onDone();
        } catch (err) {
          submitBtn.disabled = false;
          reportPluginError('CreatePlaylist', '재생목록 생성에 실패했습니다.', err);
        }
      });

      await loadLists();
    }
  });
}

/**
 * 빈 새 재생목록 생성 모달
 */
export function openCreatePlaylistModal({ onCreated = null } = {}) {
  openModal({
    title: '새 재생목록 만들기',
    bodyHtml: `
      <form id="create-playlist-form" class="form-stack">
        <div class="form-field">
          <label class="form-label" for="create-pl-input">재생목록 이름</label>
          <input id="create-pl-input" class="text-input" type="text" placeholder="예: 주말에 볼 영화" required autofocus />
        </div>
      </form>
    `,
    footerHtml: `
      <div class="cta-row">
        <button type="button" class="secondary-btn" data-cancel>취소</button>
        <button type="button" class="primary-btn" data-save>만들기</button>
      </div>
    `,
    onMount: (el, close) => {
      const input = el.querySelector('#create-pl-input');
      const saveBtn = el.querySelector('[data-save]');
      el.querySelector('[data-cancel]').addEventListener('click', close);

      const submit = async () => {
        const name = input.value.trim();
        if (!name) {
          showToast('재생목록 이름을 입력해 주세요.', 'error');
          return;
        }
        saveBtn.disabled = true;
        try {
          await api.createPlaylist(name, []);
          showToast(`'${name}' 재생목록을 생성했습니다.`, 'success');
          close();
          if (onCreated) onCreated();
        } catch (err) {
          saveBtn.disabled = false;
          reportPluginError('CreatePlaylist', '재생목록 생성에 실패했습니다.', err);
        }
      };

      saveBtn.addEventListener('click', submit);
      el.querySelector('#create-playlist-form').addEventListener('submit', (e) => {
        e.preventDefault();
        submit();
      });
    }
  });
}

/**
 * 재생목록 이름 변경 모달
 */
export function openRenamePlaylistModal(playlist, { onRenamed = null } = {}) {
  openModal({
    title: '재생목록 이름 변경',
    bodyHtml: `
      <form id="rename-playlist-form" class="form-stack">
        <div class="form-field">
          <label class="form-label" for="rename-pl-input">새 이름</label>
          <input id="rename-pl-input" class="text-input" type="text" value="${escapeHtml(playlist.Name || '')}" required autofocus />
        </div>
      </form>
    `,
    footerHtml: `
      <div class="cta-row">
        <button type="button" class="secondary-btn" data-cancel>취소</button>
        <button type="button" class="primary-btn" data-save>저장</button>
      </div>
    `,
    onMount: (el, close) => {
      const input = el.querySelector('#rename-pl-input');
      const saveBtn = el.querySelector('[data-save]');
      el.querySelector('[data-cancel]').addEventListener('click', close);

      const submit = async () => {
        const newName = input.value.trim();
        if (!newName) {
          showToast('새 이름을 입력해 주세요.', 'error');
          return;
        }
        saveBtn.disabled = true;
        try {
          await api.renamePlaylist(playlist.Id, newName);
          showToast('재생목록 이름을 변경했습니다.', 'success');
          close();
          if (onRenamed) onRenamed(newName);
        } catch (err) {
          saveBtn.disabled = false;
          reportPluginError('RenamePlaylist', '재생목록 이름 변경에 실패했습니다.', err);
        }
      };

      saveBtn.addEventListener('click', submit);
      el.querySelector('#rename-playlist-form').addEventListener('submit', (e) => {
        e.preventDefault();
        submit();
      });
    }
  });
}
