/**
 * 다음 화 자동 재생 5초 카운트다운 오버레이
 */
import { api } from '../api.js';
import { ICONS } from '../icons.js';
import { escapeHtml, formatEpisodeCode, renderFallbackImage, bindFallbackImages } from '../utils.js';

let activeTimer = null;

export function cancelNextEpisodeCountdown() {
  if (activeTimer) {
    clearInterval(activeTimer);
    activeTimer = null;
  }
  const root = document.getElementById('countdown-root');
  if (root) {
    root.classList.add('hidden');
    root.innerHTML = '';
  }
}

export function showNextEpisodeCountdown(nextEp, { onPlayNow, onCancel } = {}) {
  cancelNextEpisodeCountdown();
  const root = document.getElementById('countdown-root');
  if (!root || !nextEp) return;

  const totalSec = 5;
  let remaining = totalSec;
  const thumbUrl = api.getLandscapeImageUrl(nextEp, 540);
  const code = formatEpisodeCode(nextEp);
  const epTitle = [code, nextEp.Name].filter(Boolean).join(' · ') || nextEp.Name || '다음 에피소드';

  root.innerHTML = `
    <div class="countdown-card">
      <div class="countdown-eyebrow">다음 화 자동 재생</div>
      <div class="countdown-thumb">
        <div class="thumb-16-9">
          ${renderFallbackImage(thumbUrl, epTitle, code || epTitle)}
        </div>
      </div>
      <div class="countdown-info">
        ${nextEp.SeriesName ? `<p class="countdown-series">${escapeHtml(nextEp.SeriesName)}</p>` : ''}
        <h3 class="countdown-title">${escapeHtml(epTitle)}</h3>
        <p class="countdown-status"><strong id="countdown-sec">${remaining}</strong>초 후 자동으로 재생합니다</p>
      </div>
      <div class="countdown-progress-track">
        <div id="countdown-progress-fill" class="countdown-progress-fill" style="width:100%"></div>
      </div>
      <div class="cta-row">
        <button type="button" class="secondary-btn" id="countdown-cancel-btn">취소</button>
        <button type="button" class="primary-btn" id="countdown-play-btn">
          <span class="btn-icon">${ICONS.play}</span>
          <span>지금 재생</span>
        </button>
      </div>
    </div>
  `;

  root.classList.remove('hidden');
  bindFallbackImages(root);

  const secEl = document.getElementById('countdown-sec');
  const fillEl = document.getElementById('countdown-progress-fill');

  // 부드러운 프로그레스 감소 시작
  requestAnimationFrame(() => {
    if (fillEl) {
      fillEl.style.transition = `width ${totalSec}s linear`;
      fillEl.style.width = '0%';
    }
  });

  const triggerPlay = () => {
    cancelNextEpisodeCountdown();
    if (typeof onPlayNow === 'function') onPlayNow();
  };

  const triggerCancel = () => {
    cancelNextEpisodeCountdown();
    if (typeof onCancel === 'function') onCancel();
  };

  document.getElementById('countdown-play-btn').addEventListener('click', triggerPlay);
  document.getElementById('countdown-cancel-btn').addEventListener('click', triggerCancel);

  activeTimer = setInterval(() => {
    remaining -= 1;
    if (remaining <= 0) {
      triggerPlay();
    } else if (secEl) {
      secEl.textContent = String(remaining);
    }
  }, 1000);
}
