/**
 * 상세 화면 '...' 메뉴 및 메타데이터 조회·편집·새로고침·식별·이미지 관리·미디어 정보·2단계 삭제 모달
 */
import { api } from '../api.js';
import { isAdmin, canDeleteItem } from '../store.js';
import { ICONS } from '../icons.js';
import {
  escapeHtml,
  formatFileSize,
  formatBitrate,
  formatRuntime,
  toDateInputValue,
  getItemTypeLabel,
  renderFallbackImage,
  bindFallbackImages,
  copyToClipboard
} from '../utils.js';
import { openSheet, openModal, openTwoStepConfirmModal, openConfirmModal } from '../components/modal.js';
import { showToast, reportPluginError } from '../components/toast.js';
import { openAddToPlaylistSheet } from '../components/playlist-modal.js';

/**
 * 상세/시리즈 화면 우측 상단 '...' 바텀시트 메뉴
 */
export function openItemMoreMenuSheet(item, {
  selectedMediaSourceId = '',
  onChanged = () => {},
  onDeleted = () => {}
} = {}) {
  if (!item || !item.Id) return;

  const ud = item.UserData || {};
  const played = Boolean(ud.Played);
  const isFav = Boolean(ud.IsFavorite);
  const admin = isAdmin();
  const deletable = canDeleteItem(item);
  const hasMediaInfo = Array.isArray(item.MediaSources) && item.MediaSources.length > 0;

  openSheet({
    title: item.Name || '항목 관리',
    subtitle: getItemTypeLabel(item.Type),
    bodyHtml: `
      <div class="sheet-menu-list">
        <button type="button" class="sheet-menu-item" data-menu="played">
          <span class="sheet-menu-icon ${played ? 'accent' : ''}">${ICONS.checkCircle}</span>
          <span>${played ? '미시청으로 표시' : '시청 완료로 표시'}</span>
        </button>
        <button type="button" class="sheet-menu-item" data-menu="favorite">
          <span class="sheet-menu-icon ${isFav ? 'danger' : ''}">${isFav ? ICONS.heartFilled : ICONS.heart}</span>
          <span>${isFav ? '즐겨찾기 해제' : '즐겨찾기에 추가'}</span>
        </button>
        <button type="button" class="sheet-menu-item" data-menu="playlist">
          <span class="sheet-menu-icon">${ICONS.playlistAdd}</span>
          <span>재생목록에 추가</span>
        </button>
        ${hasMediaInfo || item.Path ? `
          <button type="button" class="sheet-menu-item" data-menu="media-info">
            <span class="sheet-menu-icon">${ICONS.info}</span>
            <span>미디어 상세 정보 보기</span>
          </button>
        ` : ''}

        ${admin ? `
          <div class="sheet-divider"></div>
          <div class="sheet-group-label">관리자 메타데이터 도구</div>
          <button type="button" class="sheet-menu-item" data-menu="edit-meta">
            <span class="sheet-menu-icon blue">${ICONS.edit}</span>
            <span>메타데이터 편집</span>
          </button>
          <button type="button" class="sheet-menu-item" data-menu="refresh-meta">
            <span class="sheet-menu-icon blue">${ICONS.refresh}</span>
            <span>메타데이터 새로고침</span>
          </button>
          <button type="button" class="sheet-menu-item" data-menu="identify">
            <span class="sheet-menu-icon blue">${ICONS.identify}</span>
            <span>식별 (Identify)</span>
          </button>
          <button type="button" class="sheet-menu-item" data-menu="images">
            <span class="sheet-menu-icon blue">${ICONS.image}</span>
            <span>이미지 관리</span>
          </button>
        ` : ''}

        ${deletable ? `
          <div class="sheet-divider"></div>
          <button type="button" class="sheet-menu-item is-danger" data-menu="delete">
            <span class="sheet-menu-icon danger">${ICONS.trash}</span>
            <span>항목 삭제</span>
          </button>
        ` : ''}
      </div>
    `,
    onMount: (el, close) => {
      const bind = (key, fn) => {
        const btn = el.querySelector(`[data-menu="${key}"]`);
        if (btn) btn.addEventListener('click', fn);
      };

      bind('played', async () => {
        close();
        try {
          const next = !played;
          await api.setPlayed(item.Id, next);
          showToast(next ? '시청 완료로 표시했습니다.' : '미시청 상태로 변경했습니다.', 'success');
          onChanged();
        } catch (err) {
          reportPluginError('TogglePlayed', '시청 상태를 변경하지 못했습니다.', err);
        }
      });

      bind('favorite', async () => {
        close();
        try {
          const next = !isFav;
          await api.setFavorite(item.Id, next);
          showToast(next ? '즐겨찾기에 추가했습니다.' : '즐겨찾기에서 해제했습니다.', 'success');
          onChanged();
        } catch (err) {
          reportPluginError('ToggleFavorite', '즐겨찾기 상태를 변경하지 못했습니다.', err);
        }
      });

      bind('playlist', () => {
        close();
        openAddToPlaylistSheet(item, { onDone: onChanged });
      });

      bind('media-info', () => {
        close();
        openMediaInfoModal(item, selectedMediaSourceId);
      });

      bind('edit-meta', () => {
        close();
        openMetadataEditModal(item, onChanged);
      });

      bind('refresh-meta', () => {
        close();
        openRefreshMetadataSheet(item, onChanged);
      });

      bind('identify', () => {
        close();
        openIdentifyModal(item, onChanged);
      });

      bind('images', () => {
        close();
        openImageManagerModal(item, onChanged);
      });

      bind('delete', () => {
        close();
        openDeleteItemConfirm(item, onDeleted);
      });
    }
  });
}

// ============================================================================
// 1. 메타데이터 편집 모달 (POST /Items/{Id})
// ============================================================================

export function openMetadataEditModal(item, onSaved = () => {}) {
  openSheet({
    title: '메타데이터 편집',
    subtitle: item.Name || '',
    fullHeight: true,
    bodyHtml: `
      <div id="meta-edit-container">
        <div class="center-state compact">
          <div class="spinner"></div>
          <p class="boot-text">전체 메타데이터를 불러오는 중...</p>
        </div>
      </div>
    `,
    footerHtml: `
      <div class="cta-row">
        <button type="button" class="secondary-btn" id="meta-edit-cancel">취소</button>
        <button type="button" class="primary-btn" id="meta-edit-save" disabled>저장</button>
      </div>
    `,
    onMount: async (el, close) => {
      const container = el.querySelector('#meta-edit-container');
      const saveBtn = el.querySelector('#meta-edit-save');
      el.querySelector('#meta-edit-cancel').addEventListener('click', close);

      let rawDto = null;
      try {
        rawDto = await api.getRawItemForEdit(item.Id);
      } catch (err) {
        container.innerHTML = `<div class="error-banner" style="margin:0;"><span>${escapeHtml(err.message || '메타데이터 로드 실패')}</span></div>`;
        reportPluginError('MetadataLoad', '메타데이터를 불러오지 못했습니다.', err);
        return;
      }

      const genresList = Array.isArray(rawDto.Genres)
        ? rawDto.Genres
        : (Array.isArray(rawDto.GenreItems) ? rawDto.GenreItems.map((g) => g.Name).filter(Boolean) : []);
      const tagsList = Array.isArray(rawDto.Tags)
        ? rawDto.Tags
        : (Array.isArray(rawDto.TagItems) ? rawDto.TagItems.map((t) => t.Name).filter(Boolean) : []);

      container.innerHTML = `
        <form id="meta-edit-form" class="form-stack">
          <div class="form-field">
            <label class="form-label" for="me-name">제목 *</label>
            <input id="me-name" class="text-input" type="text" required value="${escapeHtml(rawDto.Name || '')}" />
          </div>

          <div class="form-field">
            <label class="form-label" for="me-original">원제 (Original Title)</label>
            <input id="me-original" class="text-input" type="text" value="${escapeHtml(rawDto.OriginalTitle || '')}" placeholder="원래 제목" />
          </div>

          <div class="form-field">
            <label class="form-label" for="me-sort">정렬 이름 (Sort Name)</label>
            <input id="me-sort" class="text-input" type="text" value="${escapeHtml(rawDto.ForcedSortName || rawDto.SortName || '')}" placeholder="비워두면 제목 기준 자동 정렬" />
          </div>

          <div class="form-row-2">
            <div class="form-field">
              <label class="form-label" for="me-premiere">개봉일 / 방영일</label>
              <input id="me-premiere" class="text-input" type="date" value="${escapeHtml(toDateInputValue(rawDto.PremiereDate))}" />
            </div>
            <div class="form-field">
              <label class="form-label" for="me-rating">평점 (0 ~ 10)</label>
              <input id="me-rating" class="text-input" type="number" step="0.1" min="0" max="10" value="${rawDto.CommunityRating != null ? escapeHtml(rawDto.CommunityRating) : ''}" placeholder="예: 8.5" />
            </div>
          </div>

          <div class="form-field">
            <label class="form-label" for="me-overview">줄거리 (Overview)</label>
            <textarea id="me-overview" class="text-area" rows="5" placeholder="작품 설명을 입력하세요...">${escapeHtml(rawDto.Overview || '')}</textarea>
          </div>

          <div class="form-field">
            <label class="form-label" for="me-genres">장르 (쉼표로 구분)</label>
            <input id="me-genres" class="text-input" type="text" value="${escapeHtml(genresList.join(', '))}" placeholder="액션, 드라마, SF" />
          </div>

          <div class="form-field">
            <label class="form-label" for="me-tags">태그 (쉼표로 구분)</label>
            <input id="me-tags" class="text-input" type="text" value="${escapeHtml(tagsList.join(', '))}" placeholder="4K, 추천작" />
          </div>

          <label class="switch-row">
            <div class="switch-text">
              <span class="switch-title">메타데이터 잠금 (LockData)</span>
              <span class="switch-desc">자동 스캔 시 수정한 메타데이터가 덮어쓰이지 않도록 보호합니다.</span>
            </div>
            <input id="me-lock" type="checkbox" class="toggle-switch" ${rawDto.LockData ? 'checked' : ''} />
          </label>
        </form>
      `;

      saveBtn.disabled = false;

      saveBtn.addEventListener('click', async () => {
        const name = container.querySelector('#me-name').value.trim();
        if (!name) {
          showToast('제목을 입력해 주세요.', 'error');
          return;
        }
        const originalTitle = container.querySelector('#me-original').value.trim();
        const sortName = container.querySelector('#me-sort').value.trim();
        const premiereVal = container.querySelector('#me-premiere').value.trim();
        const ratingRaw = container.querySelector('#me-rating').value.trim();
        const overview = container.querySelector('#me-overview').value;
        const genres = container.querySelector('#me-genres').value
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean);
        const tags = container.querySelector('#me-tags').value
          .split(',')
          .map((s) => s.trim())
          .filter(Boolean);
        const lockData = container.querySelector('#me-lock').checked;

        const payload = { ...rawDto };
        payload.Name = name;
        payload.OriginalTitle = originalTitle || null;
        payload.ForcedSortName = sortName || null;
        if (sortName) payload.SortName = sortName;
        payload.Overview = overview;
        payload.Genres = genres;
        payload.GenreItems = genres.map((g) => ({ Name: g }));
        payload.Tags = tags;
        payload.TagItems = tags.map((t) => ({ Name: t }));
        payload.LockData = lockData;

        if (premiereVal) {
          payload.PremiereDate = `${premiereVal}T00:00:00.0000000Z`;
          const yr = parseInt(premiereVal.slice(0, 4), 10);
          if (!Number.isNaN(yr)) payload.ProductionYear = yr;
        } else {
          payload.PremiereDate = null;
        }

        if (ratingRaw !== '') {
          const parsedRating = parseFloat(ratingRaw);
          if (!Number.isNaN(parsedRating)) {
            payload.CommunityRating = Math.min(10, Math.max(0, parsedRating));
          }
        } else {
          payload.CommunityRating = null;
        }

        saveBtn.disabled = true;
        saveBtn.textContent = '저장 중...';
        try {
          await api.updateItemMetadata(item.Id, payload);
          showToast('메타데이터를 저장했습니다.', 'success');
          close();
          onSaved();
        } catch (err) {
          saveBtn.disabled = false;
          saveBtn.textContent = '저장';
          reportPluginError('MetadataSave', '메타데이터 저장에 실패했습니다.', err);
        }
      });
    }
  });
}

// ============================================================================
// 2. 메타데이터 새로고침 시트 (POST /Items/{Id}/Refresh)
// ============================================================================

export function openRefreshMetadataSheet(item, onRefreshed = () => {}) {
  openSheet({
    title: '메타데이터 새로고침',
    subtitle: item.Name || '',
    bodyHtml: `
      <div class="form-stack">
        <div class="form-field">
          <span class="form-label">새로고침 모드</span>
          <div class="radio-card-group">
            <label class="radio-card">
              <input type="radio" name="refresh-mode" value="scan" />
              <div class="radio-card-body">
                <strong>새 파일 및 업데이트된 파일 스캔</strong>
                <span>추가·변경된 미디어 파일만 빠르게 확인합니다.</span>
              </div>
            </label>
            <label class="radio-card">
              <input type="radio" name="refresh-mode" value="missing" checked />
              <div class="radio-card-body">
                <strong>누락된 메타데이터 검색</strong>
                <span>비어 있는 줄거리·이미지·정보만 원격에서 채웁니다.</span>
              </div>
            </label>
            <label class="radio-card">
              <input type="radio" name="refresh-mode" value="replace" />
              <div class="radio-card-body">
                <strong>모든 메타데이터 교체</strong>
                <span>잠기지 않은 모든 텍스트 메타데이터를 새로 가져옵니다.</span>
              </div>
            </label>
          </div>
        </div>

        <label class="checkbox-row">
          <input type="checkbox" id="refresh-replace-images" />
          <span>기존 이미지 교체 (포스터·배경·로고 다시 다운로드)</span>
        </label>
      </div>
    `,
    footerHtml: `
      <div class="cta-row">
        <button type="button" class="secondary-btn" data-cancel>취소</button>
        <button type="button" class="primary-btn" data-run>새로고침 실행</button>
      </div>
    `,
    onMount: (el, close) => {
      el.querySelector('[data-cancel]').addEventListener('click', close);
      const runBtn = el.querySelector('[data-run]');
      runBtn.addEventListener('click', async () => {
        const modeInput = el.querySelector('input[name="refresh-mode"]:checked');
        const mode = modeInput ? modeInput.value : 'missing';
        const replaceAllImages = Boolean(el.querySelector('#refresh-replace-images').checked);
        runBtn.disabled = true;
        runBtn.textContent = '요청 중...';
        try {
          await api.refreshItemMetadata(item.Id, { mode, replaceAllImages });
          showToast('메타데이터 새로고침 작업을 시작했습니다.', 'success');
          close();
          setTimeout(() => onRefreshed(), 1200);
        } catch (err) {
          runBtn.disabled = false;
          runBtn.textContent = '새로고침 실행';
          reportPluginError('RefreshMetadata', '메타데이터 새로고침 요청에 실패했습니다.', err);
        }
      });
    }
  });
}

// ============================================================================
// 3. 식별(Identify) 모달 (POST /Items/RemoteSearch/... -> Apply)
// ============================================================================

export function openIdentifyModal(item, onApplied = () => {}) {
  const providerIds = item.ProviderIds || {};

  openSheet({
    title: '콘텐츠 식별 (Identify)',
    subtitle: `${getItemTypeLabel(item.Type)} · ${item.Name || ''}`,
    fullHeight: true,
    bodyHtml: `
      <form id="identify-form" class="form-stack">
        <div class="form-field">
          <label class="form-label" for="id-name">제목</label>
          <input id="id-name" class="text-input" type="text" value="${escapeHtml(item.Name || '')}" placeholder="작품 제목 입력" />
        </div>
        <div class="form-row-3">
          <div class="form-field">
            <label class="form-label" for="id-year">연도</label>
            <input id="id-year" class="text-input" type="number" value="${escapeHtml(item.ProductionYear || '')}" placeholder="2024" />
          </div>
          <div class="form-field">
            <label class="form-label" for="id-tmdb">TMDB ID</label>
            <input id="id-tmdb" class="text-input" type="text" value="${escapeHtml(providerIds.Tmdb || '')}" placeholder="예: 157336" />
          </div>
          <div class="form-field">
            <label class="form-label" for="id-imdb">IMDb ID</label>
            <input id="id-imdb" class="text-input" type="text" value="${escapeHtml(providerIds.Imdb || '')}" placeholder="tt0816692" />
          </div>
        </div>
        <label class="checkbox-row">
          <input type="checkbox" id="id-replace-images" checked />
          <span>식별 적용 시 기존 이미지도 함께 교체</span>
        </label>
        <button type="submit" class="primary-btn" id="id-search-btn">원격 메타데이터 검색</button>
      </form>

      <div id="identify-results" class="identify-results">
        <p class="empty-inline">제목 또는 외부 ID(TMDB/IMDb)를 입력하고 검색해 주세요.</p>
      </div>
    `,
    onMount: (el, close) => {
      const form = el.querySelector('#identify-form');
      const resultsEl = el.querySelector('#identify-results');
      const searchBtn = el.querySelector('#id-search-btn');

      form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const name = el.querySelector('#id-name').value.trim();
        const year = el.querySelector('#id-year').value.trim();
        const tmdbId = el.querySelector('#id-tmdb').value.trim();
        const imdbId = el.querySelector('#id-imdb').value.trim();

        if (!name && !tmdbId && !imdbId) {
          showToast('제목 또는 TMDB/IMDb ID를 하나 이상 입력해 주세요.', 'error');
          return;
        }

        searchBtn.disabled = true;
        searchBtn.textContent = '검색 중...';
        resultsEl.innerHTML = '<div class="center-state compact"><div class="spinner"></div><p class="boot-text">원격 제공자에서 검색 중...</p></div>';

        try {
          const results = await api.remoteSearch(item, { name, year, tmdbId, imdbId });
          if (results.length === 0) {
            resultsEl.innerHTML = '<p class="empty-inline">일치하는 원격 검색 결과가 없습니다. 영문 제목이나 TMDB ID로 다시 시도해 보세요.</p>';
          } else {
            resultsEl.innerHTML = results.map((res, idx) => {
              const imgUrl = res.ImageUrl ? api.proxyUrl(res.ImageUrl) : '';
              const pids = res.ProviderIds
                ? Object.entries(res.ProviderIds).map(([k, v]) => `${k}: ${v}`).join(' · ')
                : '';
              return `
                <button type="button" class="identify-card" data-result-idx="${idx}">
                  <div class="identify-thumb">
                    <div class="poster-2-3">${renderFallbackImage(imgUrl, res.Name || '', res.Name || '?')}</div>
                  </div>
                  <div class="identify-info">
                    <div class="identify-title">${escapeHtml(res.Name || '제목 없음')}</div>
                    <div class="identify-sub">${escapeHtml([res.ProductionYear ? `${res.ProductionYear}년` : '', res.SearchProviderName || ''].filter(Boolean).join(' · '))}</div>
                    ${pids ? `<div class="identify-pids">${escapeHtml(pids)}</div>` : ''}
                    ${res.Overview ? `<p class="identify-overview">${escapeHtml(res.Overview)}</p>` : ''}
                  </div>
                </button>
              `;
            }).join('');

            bindFallbackImages(resultsEl);

            resultsEl.querySelectorAll('[data-result-idx]').forEach((card) => {
              card.addEventListener('click', () => {
                const chosen = results[Number(card.dataset.resultIdx)];
                if (!chosen) return;
                const replaceImages = Boolean(el.querySelector('#id-replace-images').checked);
                openConfirmModal({
                  title: '식별 결과 적용',
                  message: `'${chosen.Name || ''}'(${chosen.ProductionYear || '연도 미상'}) 정보로 메타데이터를 교체할까요?`,
                  confirmLabel: '적용하기',
                  onConfirm: async () => {
                    try {
                      await api.applyRemoteSearch(item.Id, chosen, replaceImages);
                      showToast('식별 결과를 적용했습니다. 잠시 후 메타데이터가 갱신됩니다.', 'success');
                      close();
                      setTimeout(() => onApplied(), 1000);
                    } catch (err) {
                      reportPluginError('IdentifyApply', '식별 결과 적용에 실패했습니다.', err);
                      throw err;
                    }
                  }
                });
              });
            });
          }
        } catch (err) {
          resultsEl.innerHTML = `<div class="error-banner" style="margin:0;"><span>${escapeHtml(err.message || '원격 검색 실패')}</span></div>`;
          reportPluginError('RemoteSearch', '원격 식별 검색에 실패했습니다.', err);
        } finally {
          searchBtn.disabled = false;
          searchBtn.textContent = '원격 메타데이터 검색';
        }
      });
    }
  });
}

// ============================================================================
// 4. 이미지 관리 모달 (/Items/{Id}/Images, /Items/{Id}/RemoteImages)
// ============================================================================

const IMAGE_TYPE_TABS = [
  { key: 'Primary', label: '포스터 (Primary)' },
  { key: 'Backdrop', label: '배경 (Backdrop)' },
  { key: 'Logo', label: '로고 (Logo)' }
];

export function openImageManagerModal(item, onUpdated = () => {}) {
  let activeMode = 'current'; // 'current' | 'remote'
  let remoteType = 'Primary';
  let mutated = false;

  openSheet({
    title: '이미지 관리',
    subtitle: item.Name || '',
    fullHeight: true,
    bodyHtml: `
      <div class="segmented-tabs" id="img-mgr-tabs">
        <button type="button" class="seg-tab active" data-tab="current">현재 등록 이미지</button>
        <button type="button" class="seg-tab" data-tab="remote">원격 이미지 검색</button>
      </div>
      <div id="img-mgr-type-bar" class="chip-row hidden">
        ${IMAGE_TYPE_TABS.map((t) => `
          <button type="button" class="chip-btn ${t.key === remoteType ? 'active' : ''}" data-img-type="${t.key}">${t.label}</button>
        `).join('')}
      </div>
      <div id="img-mgr-content"></div>
    `,
    onClose: () => {
      if (mutated) onUpdated();
    },
    onMount: (el) => {
      const contentEl = el.querySelector('#img-mgr-content');
      const typeBarEl = el.querySelector('#img-mgr-type-bar');

      const loadCurrentImages = async () => {
        contentEl.innerHTML = '<div class="center-state compact"><div class="spinner"></div><p class="boot-text">등록된 이미지 불러오는 중...</p></div>';
        try {
          const images = await api.getItemImages(item.Id);
          const relevant = images.filter((img) => ['Primary', 'Backdrop', 'Logo', 'Thumb', 'Banner'].includes(img.ImageType));
          if (relevant.length === 0) {
            contentEl.innerHTML = '<p class="empty-inline">현재 등록된 이미지가 없습니다. "원격 이미지 검색" 탭에서 추가할 수 있습니다.</p>';
            return;
          }

          const typeLabelMap = {
            Primary: '포스터',
            Backdrop: '배경',
            Logo: '로고',
            Thumb: '썸네일',
            Banner: '배너'
          };

          contentEl.innerHTML = `
            <div class="image-mgr-grid">
              ${relevant.map((img, i) => {
                const idx = img.ImageIndex != null ? img.ImageIndex : null;
                const url = api.buildImageUrl(item.Id, img.ImageType, 480, img.ImageTag || String(Date.now()), idx);
                const dims = (img.Width && img.Height) ? `${img.Width}×${img.Height}` : '';
                const label = typeLabelMap[img.ImageType] || img.ImageType;
                return `
                  <div class="image-mgr-card">
                    <div class="image-mgr-preview ${img.ImageType === 'Backdrop' || img.ImageType === 'Logo' ? 'is-wide' : ''}">
                      <img src="${escapeHtml(url)}" alt="${escapeHtml(label)}" loading="lazy" />
                    </div>
                    <div class="image-mgr-meta">
                      <div>
                        <div class="image-mgr-type">${escapeHtml(label)}${idx !== null ? ` #${idx + 1}` : ''}</div>
                        <div class="image-mgr-dim">${escapeHtml([dims, img.Size ? formatFileSize(img.Size) : ''].filter(Boolean).join(' · ') || '등록됨')}</div>
                      </div>
                      <button type="button" class="icon-btn danger-icon-btn" data-del-img="${i}" aria-label="이미지 삭제">${ICONS.trash}</button>
                    </div>
                  </div>
                `;
              }).join('')}
            </div>
          `;

          contentEl.querySelectorAll('[data-del-img]').forEach((btn) => {
            btn.addEventListener('click', () => {
              const target = relevant[Number(btn.dataset.delImg)];
              if (!target) return;
              openConfirmModal({
                title: '이미지 삭제',
                message: `${typeLabelMap[target.ImageType] || target.ImageType} 이미지를 삭제할까요?`,
                confirmLabel: '삭제',
                danger: true,
                onConfirm: async () => {
                  try {
                    await api.deleteItemImage(item.Id, target.ImageType, target.ImageIndex != null ? target.ImageIndex : null);
                    mutated = true;
                    showToast('이미지를 삭제했습니다.', 'success');
                    await loadCurrentImages();
                  } catch (err) {
                    reportPluginError('DeleteImage', '이미지 삭제에 실패했습니다.', err);
                    throw err;
                  }
                }
              });
            });
          });
        } catch (err) {
          contentEl.innerHTML = `<div class="error-banner" style="margin:0;"><span>${escapeHtml(err.message || '이미지 목록 로드 실패')}</span></div>`;
          reportPluginError('LoadImages', '이미지 목록을 불러오지 못했습니다.', err);
        }
      };

      const loadRemoteImages = async () => {
        contentEl.innerHTML = '<div class="center-state compact"><div class="spinner"></div><p class="boot-text">원격 이미지를 검색하는 중...</p></div>';
        try {
          const images = await api.getRemoteImages(item.Id, remoteType, 0, 30);
          if (images.length === 0) {
            contentEl.innerHTML = '<p class="empty-inline">해당 유형의 원격 이미지를 찾지 못했습니다.</p>';
            return;
          }

          const isWide = remoteType === 'Backdrop' || remoteType === 'Logo';
          contentEl.innerHTML = `
            <div class="image-mgr-grid">
              ${images.map((img, idx) => {
                const previewUrl = api.proxyUrl(img.ThumbnailUrl || img.Url || '');
                const dims = (img.Width && img.Height) ? `${img.Width}×${img.Height}` : '';
                const info = [dims, img.Language ? img.Language.toUpperCase() : '', img.ProviderName || ''].filter(Boolean).join(' · ');
                return `
                  <button type="button" class="image-mgr-card clickable" data-apply-remote="${idx}">
                    <div class="image-mgr-preview ${isWide ? 'is-wide' : ''}">
                      <img src="${escapeHtml(previewUrl)}" alt="${escapeHtml(remoteType)}" loading="lazy" />
                    </div>
                    <div class="image-mgr-meta">
                      <div>
                        <div class="image-mgr-type">${escapeHtml(img.ProviderName || '원격 이미지')}</div>
                        <div class="image-mgr-dim">${escapeHtml(info || '탭하여 적용')}</div>
                      </div>
                      <span class="apply-pill">적용</span>
                    </div>
                  </button>
                `;
              }).join('')}
            </div>
          `;

          contentEl.querySelectorAll('[data-apply-remote]').forEach((card) => {
            card.addEventListener('click', async () => {
              const target = images[Number(card.dataset.applyRemote)];
              if (!target || !target.Url) return;
              card.disabled = true;
              const pill = card.querySelector('.apply-pill');
              if (pill) pill.textContent = '적용 중...';
              try {
                await api.downloadRemoteImage(item.Id, {
                  type: target.Type || remoteType,
                  imageUrl: target.Url,
                  providerName: target.ProviderName || ''
                });
                mutated = true;
                showToast('새 이미지를 적용했습니다.', 'success');
                if (pill) pill.textContent = '적용됨';
              } catch (err) {
                card.disabled = false;
                if (pill) pill.textContent = '적용';
                reportPluginError('ApplyRemoteImage', '원격 이미지 적용에 실패했습니다.', err);
              }
            });
          });
        } catch (err) {
          contentEl.innerHTML = `<div class="error-banner" style="margin:0;"><span>${escapeHtml(err.message || '원격 이미지 검색 실패')}</span></div>`;
          reportPluginError('RemoteImages', '원격 이미지 검색에 실패했습니다.', err);
        }
      };

      const renderActiveTab = () => {
        el.querySelectorAll('#img-mgr-tabs [data-tab]').forEach((btn) => {
          btn.classList.toggle('active', btn.dataset.tab === activeMode);
        });
        typeBarEl.classList.toggle('hidden', activeMode !== 'remote');
        if (activeMode === 'current') loadCurrentImages();
        else loadRemoteImages();
      };

      el.querySelectorAll('#img-mgr-tabs [data-tab]').forEach((btn) => {
        btn.addEventListener('click', () => {
          activeMode = btn.dataset.tab;
          renderActiveTab();
        });
      });

      typeBarEl.querySelectorAll('[data-img-type]').forEach((btn) => {
        btn.addEventListener('click', () => {
          remoteType = btn.dataset.imgType;
          typeBarEl.querySelectorAll('[data-img-type]').forEach((b) => {
            b.classList.toggle('active', b.dataset.imgType === remoteType);
          });
          loadRemoteImages();
        });
      });

      renderActiveTab();
    }
  });
}

// ============================================================================
// 5. 미디어 상세 정보 보기 모달
// ============================================================================

export function openMediaInfoModal(item, selectedMediaSourceId = '') {
  const sources = Array.isArray(item.MediaSources) ? item.MediaSources : [];
  const ms = (selectedMediaSourceId && sources.find((s) => s.Id === selectedMediaSourceId))
    || sources[0]
    || null;
  const streams = (ms && Array.isArray(ms.MediaStreams))
    ? ms.MediaStreams
    : (Array.isArray(item.MediaStreams) ? item.MediaStreams : []);

  const videoStreams = streams.filter((s) => s.Type === 'Video');
  const audioStreams = streams.filter((s) => s.Type === 'Audio');
  const subStreams = streams.filter((s) => s.Type === 'Subtitle');
  const filePath = (ms && ms.Path) || item.Path || '-';

  const renderKv = (rows) => `
    <div class="info-kv">
      ${rows.filter(([, v]) => v !== '' && v != null).map(([k, v]) => `
        <div class="info-kv-row">
          <span class="info-kv-key">${escapeHtml(k)}</span>
          <span class="info-kv-val">${escapeHtml(v)}</span>
        </div>
      `).join('')}
    </div>
  `;

  const videoHtml = videoStreams.map((v, idx) => renderKv([
    [`비디오 #${idx + 1}`, v.DisplayTitle || v.Title || (v.Codec ? v.Codec.toUpperCase() : '-')],
    ['코덱 / 프로파일', [v.Codec && v.Codec.toUpperCase(), v.Profile].filter(Boolean).join(' · ') || '-'],
    ['해상도', (v.Width && v.Height) ? `${v.Width} × ${v.Height}` : '-'],
    ['비트레이트', formatBitrate(v.BitRate)],
    ['프레임률', v.RealFrameRate ? `${Number(v.RealFrameRate).toFixed(3)} fps` : (v.AverageFrameRate ? `${Number(v.AverageFrameRate).toFixed(2)} fps` : '')],
    ['색역 / 비트심도', [v.VideoRange, v.ColorSpace, v.BitDepth ? `${v.BitDepth}-bit` : ''].filter(Boolean).join(' · ')]
  ])).join('');

  const audioHtml = audioStreams.map((a, idx) => renderKv([
    [`오디오 #${idx + 1}`, a.DisplayTitle || a.Title || a.Language || '트랙'],
    ['코덱 / 채널', [a.Codec && a.Codec.toUpperCase(), a.ChannelLayout || (a.Channels ? `${a.Channels}ch` : '')].filter(Boolean).join(' · ')],
    ['언어 / 비트레이트', [a.Language, a.BitRate ? formatBitrate(a.BitRate) : ''].filter(Boolean).join(' · ')]
  ])).join('');

  const subHtml = subStreams.map((s, idx) => renderKv([
    [`자막 #${idx + 1}`, s.DisplayTitle || s.Title || s.Language || '자막'],
    ['형식', [s.Codec && s.Codec.toUpperCase(), s.IsExternal ? '외부 파일' : '컨테이너 내장'].filter(Boolean).join(' · ')],
    ['언어', s.Language || '미지정']
  ])).join('');

  openSheet({
    title: '미디어 상세 정보',
    subtitle: (ms && ms.Name) || item.Name || '',
    fullHeight: true,
    bodyHtml: `
      <div class="form-stack">
        <div class="sheet-group-label">컨테이너 및 파일</div>
        ${renderKv([
          ['컨테이너', ((ms && ms.Container) || item.Container || '-').toUpperCase()],
          ['파일 크기', ms && ms.Size ? formatFileSize(ms.Size) : '-'],
          ['전체 비트레이트', ms && ms.Bitrate ? formatBitrate(ms.Bitrate) : '-'],
          ['재생 시간', formatRuntime((ms && ms.RunTimeTicks) || item.RunTimeTicks) || '-'],
          ['Direct Play', ms && ms.SupportsDirectPlay ? '지원' : '확인 필요']
        ])}

        <div class="sheet-group-label">서버 내 실제 파일 경로</div>
        <div class="path-copy-box">
          <code class="path-text">${escapeHtml(filePath)}</code>
          ${filePath && filePath !== '-' ? `<button type="button" class="icon-btn" id="copy-file-path" aria-label="경로 복사">${ICONS.copy}</button>` : ''}
        </div>

        ${videoHtml ? `<div class="sheet-group-label">비디오 스트림 (${videoStreams.length})</div>${videoHtml}` : ''}
        ${audioHtml ? `<div class="sheet-group-label">오디오 스트림 (${audioStreams.length})</div>${audioHtml}` : ''}
        ${subHtml ? `<div class="sheet-group-label">자막 스트림 (${subStreams.length})</div>${subHtml}` : ''}
      </div>
    `,
    onMount: (el) => {
      const copyBtn = el.querySelector('#copy-file-path');
      if (copyBtn) {
        copyBtn.addEventListener('click', async () => {
          const ok = await copyToClipboard(filePath);
          showToast(ok ? '파일 경로를 클립보드에 복사했습니다.' : '경로 복사에 실패했습니다.', ok ? 'success' : 'error');
        });
      }
    }
  });
}

// ============================================================================
// 6. 항목 삭제 2단계 확인 모달 (DELETE /Items/{Id})
// ============================================================================

export function openDeleteItemConfirm(item, onDeleted = () => {}) {
  const filePath = (item.MediaSources && item.MediaSources[0] && item.MediaSources[0].Path) || item.Path || '';

  openTwoStepConfirmModal({
    title: '항목 영구 삭제',
    step1Message: `'${item.Name || '선택한 항목'}'을(를) Emby 서버에서 삭제하시겠습니까?`,
    detailText: filePath ? `파일 경로: ${filePath}` : `항목 ID: ${item.Id}`,
    step2Warning: '주의: Emby 설정에 따라 서버 디스크의 원본 미디어 파일까지 영구 삭제될 수 있습니다. 삭제 후에는 복구할 수 없습니다.',
    finalButtonLabel: '영구 삭제',
    onConfirm: async () => {
      try {
        await api.deleteItem(item.Id);
        showToast(`'${item.Name || '항목'}'을(를) 삭제했습니다.`, 'success');
        onDeleted();
      } catch (err) {
        reportPluginError('DeleteItem', '항목 삭제에 실패했습니다.', err);
        throw err;
      }
    }
  });
}
