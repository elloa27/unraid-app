/**
 * 바텀시트 및 센터 모달 스택 관리자
 * - 여러 오버레이(예: 메뉴 바텀시트 -> 편집 모달 -> 확인 모달)를 안전하게 중첩/해제합니다.
 */
import { ICONS } from '../icons.js';
import { escapeHtml } from '../utils.js';

const overlayStack = [];

function getOverlayRoot() {
  return document.getElementById('overlay-root');
}

export function hasOpenOverlay() {
  return overlayStack.length > 0;
}

export function closeTopOverlay() {
  const top = overlayStack.pop();
  if (!top) return false;
  if (top.backdrop && top.backdrop.parentNode) {
    top.backdrop.remove();
  }
  if (typeof top.onClose === 'function') {
    try { top.onClose(); } catch (_) {}
  }
  return true;
}

export function closeAllOverlays() {
  while (overlayStack.length > 0) {
    closeTopOverlay();
  }
}

/**
 * 바텀시트 또는 중앙 모달 생성
 * @param {object} opts
 * @param {string} opts.title 제목
 * @param {string} opts.subtitle 부제목 (선택)
 * @param {string} opts.bodyHtml 내부 HTML
 * @param {string} opts.footerHtml 하단 고정 푸터 HTML (선택)
 * @param {'sheet'|'modal'} opts.variant 표시 방식 (기본 'sheet')
 * @param {boolean} opts.fullHeight 전체 높이에 가깝게 표시할지 여부
 * @param {function} opts.onMount (el, closeFn) => void
 * @param {function} opts.onClose () => void
 */
export function openOverlay({
  title = '',
  subtitle = '',
  bodyHtml = '',
  footerHtml = '',
  variant = 'sheet',
  fullHeight = false,
  dismissible = true,
  onMount = null,
  onClose = null
}) {
  const root = getOverlayRoot();
  if (!root) return { close: () => {} };

  const backdrop = document.createElement('div');
  backdrop.className = `overlay-backdrop ${variant === 'modal' ? 'is-center' : 'is-sheet'}`;
  backdrop.setAttribute('role', 'dialog');
  backdrop.setAttribute('aria-modal', 'true');

  const panelClass = variant === 'modal'
    ? 'center-modal-panel'
    : `bottom-sheet-panel ${fullHeight ? 'is-tall' : ''}`;

  backdrop.innerHTML = `
    <div class="${panelClass}">
      ${variant === 'sheet' ? '<div class="sheet-handle-bar"><span></span></div>' : ''}
      ${title ? `
        <div class="overlay-header">
          <div class="overlay-title-wrap">
            <h2 class="overlay-title">${escapeHtml(title)}</h2>
            ${subtitle ? `<p class="overlay-subtitle">${escapeHtml(subtitle)}</p>` : ''}
          </div>
          ${dismissible ? `<button type="button" class="icon-btn overlay-close-btn" aria-label="닫기">${ICONS.close}</button>` : ''}
        </div>
      ` : ''}
      <div class="overlay-body">${bodyHtml}</div>
      ${footerHtml ? `<div class="overlay-footer">${footerHtml}</div>` : ''}
    </div>
  `;

  const entry = { backdrop, onClose };
  overlayStack.push(entry);
  root.appendChild(backdrop);

  const close = () => {
    const idx = overlayStack.indexOf(entry);
    if (idx !== -1) {
      overlayStack.splice(idx, 1);
    }
    if (backdrop.parentNode) backdrop.remove();
    if (typeof onClose === 'function') {
      try { onClose(); } catch (_) {}
    }
  };

  if (dismissible) {
    backdrop.addEventListener('click', (e) => {
      if (e.target === backdrop) close();
    });
    const closeBtn = backdrop.querySelector('.overlay-close-btn');
    if (closeBtn) closeBtn.addEventListener('click', close);
  }

  if (typeof onMount === 'function') {
    onMount(backdrop, close);
  }

  return { el: backdrop, close };
}

export function openSheet(opts) {
  return openOverlay({ ...opts, variant: 'sheet' });
}

export function openModal(opts) {
  return openOverlay({ ...opts, variant: 'modal' });
}

/**
 * 1단계 단순 확인 모달
 */
export function openConfirmModal({
  title,
  message,
  confirmLabel = '확인',
  cancelLabel = '취소',
  danger = false,
  onConfirm
}) {
  return openModal({
    title,
    bodyHtml: `<p class="confirm-desc">${escapeHtml(message)}</p>`,
    footerHtml: `
      <div class="cta-row">
        <button type="button" class="secondary-btn" data-confirm-cancel>${escapeHtml(cancelLabel)}</button>
        <button type="button" class="${danger ? 'danger-solid-btn' : 'primary-btn'}" data-confirm-ok>${escapeHtml(confirmLabel)}</button>
      </div>
    `,
    onMount: (el, close) => {
      el.querySelector('[data-confirm-cancel]').addEventListener('click', close);
      const okBtn = el.querySelector('[data-confirm-ok]');
      okBtn.addEventListener('click', async () => {
        okBtn.disabled = true;
        try {
          await onConfirm();
          close();
        } catch (_) {
          okBtn.disabled = false;
        }
      });
    }
  });
}

/**
 * 실수 방지용 2단계 확인 모달 (항목 삭제 등 복구 불가능한 작업 전용)
 */
export function openTwoStepConfirmModal({
  title,
  step1Message,
  detailText = '',
  step2Warning,
  finalButtonLabel = '영구 삭제',
  onConfirm
}) {
  let step = 1;
  return openModal({
    title,
    bodyHtml: '<div data-two-step-body></div>',
    footerHtml: '<div data-two-step-footer></div>',
    onMount: (el, close) => {
      const bodyEl = el.querySelector('[data-two-step-body]');
      const footerEl = el.querySelector('[data-two-step-footer]');

      const renderStep = () => {
        if (step === 1) {
          bodyEl.innerHTML = `
            <div class="step-indicator">1 / 2 단계 확인</div>
            <p class="confirm-desc">${escapeHtml(step1Message)}</p>
            ${detailText ? `<div class="code-box">${escapeHtml(detailText)}</div>` : ''}
          `;
          footerEl.innerHTML = `
            <div class="cta-row">
              <button type="button" class="secondary-btn" data-cancel>취소</button>
              <button type="button" class="danger-btn" data-next>다음 단계로</button>
            </div>
          `;
          footerEl.querySelector('[data-cancel]').addEventListener('click', close);
          footerEl.querySelector('[data-next]').addEventListener('click', () => {
            step = 2;
            renderStep();
          });
        } else {
          bodyEl.innerHTML = `
            <div class="step-indicator danger">2 / 2 최종 확인</div>
            <div class="warning-callout">${escapeHtml(step2Warning)}</div>
            <label class="checkbox-row">
              <input type="checkbox" id="two-step-agree" />
              <span>이 작업은 되돌릴 수 없음을 이해했습니다.</span>
            </label>
          `;
          footerEl.innerHTML = `
            <div class="cta-row">
              <button type="button" class="secondary-btn" data-prev>이전</button>
              <button type="button" class="danger-solid-btn" data-execute disabled>${escapeHtml(finalButtonLabel)}</button>
            </div>
          `;
          const agree = bodyEl.querySelector('#two-step-agree');
          const execBtn = footerEl.querySelector('[data-execute]');
          agree.addEventListener('change', () => {
            execBtn.disabled = !agree.checked;
          });
          footerEl.querySelector('[data-prev]').addEventListener('click', () => {
            step = 1;
            renderStep();
          });
          execBtn.addEventListener('click', async () => {
            execBtn.disabled = true;
            execBtn.textContent = '처리 중...';
            try {
              await onConfirm();
              close();
            } catch (_) {
              execBtn.disabled = false;
              execBtn.textContent = finalButtonLabel;
            }
          });
        }
      };

      renderStep();
    }
  });
}
