/**
 * 내장 libmpv 재생, Emby 진행률 동기화, 에피소드 전환 및 5초 다음 화 자동 재생 컨트롤러
 */
import { api } from './api.js';
import { state, currentView, triggerRender } from './store.js';
import { formatEpisodeNavLabel } from './utils.js';
import { showToast, reportPluginError } from './components/toast.js';
import { showNextEpisodeCountdown, cancelNextEpisodeCountdown } from './components/countdown.js';

let onAfterPlaybackRefresh = async () => {};

export function setAfterPlaybackRefreshHandler(fn) {
  onAfterPlaybackRefresh = typeof fn === 'function' ? fn : async () => {};
}

export async function playEmbyItem(item, options = {}) {
  cancelNextEpisodeCountdown();
  const {
    resume = true,
    startPositionSecOverride = null,
    mediaSourceId = '',
    audioStreamIndex = null,
    subtitleSelection = 'auto',
    knownSiblings = null
  } = options;

  try {
    let fullItem = item;
    const hasMediaSources = Array.isArray(item.MediaSources) && item.MediaSources.length > 0;
    if (!hasMediaSources) {
      showToast('스트림 정보를 불러오는 중...');
      fullItem = await api.getItemDetail(item.Id);
    }

    // 에피소드인 경우 같은 시즌 목록 확보해 이전화/다음화 전환 및 자동 재생 지원
    let siblings = Array.isArray(knownSiblings) && knownSiblings.length > 0 ? knownSiblings : [];
    if (fullItem && fullItem.Type === 'Episode' && fullItem.SeriesId && siblings.length === 0) {
      try {
        siblings = await api.getEpisodes(fullItem.SeriesId, fullItem.SeasonId || '');
      } catch (_) {
        siblings = [];
      }
    }

    let prevEp = null;
    let nextEp = null;
    if (fullItem && fullItem.Type === 'Episode' && siblings.length > 0) {
      const idx = siblings.findIndex((e) => e.Id === fullItem.Id);
      if (idx > 0) prevEp = siblings[idx - 1];
      if (idx >= 0 && idx < siblings.length - 1) nextEp = siblings[idx + 1];
    }

    const navInfo = {
      hasPrevious: Boolean(prevEp),
      hasNext: Boolean(nextEp),
      previousTitle: formatEpisodeNavLabel(prevEp),
      nextTitle: formatEpisodeNavLabel(nextEp)
    };

    const { session, playVideoOptions } = api.buildDirectPlayRequest(fullItem, {
      resume,
      startPositionSecOverride,
      mediaSourceId,
      audioStreamIndex,
      subtitleSelection,
      preferredSubLang: state.prefs.preferredSubLang || 'kor',
      navInfo
    });

    state.activePlaybackSession = session;
    state.activePlaybackContext = {
      item: fullItem,
      siblings,
      prevEp,
      nextEp
    };

    api.reportPlaybackStarted(session, playVideoOptions.startPositionSec).catch((err) => {
      if (window.UnraidHub && window.UnraidHub.logError) {
        window.UnraidHub.logError('PlaybackStart', 'Emby 재생 시작 보고 실패', err.detail || err.message);
      }
    });

    await window.UnraidHub.playVideo(playVideoOptions);
  } catch (err) {
    state.activePlaybackSession = null;
    state.activePlaybackContext = null;
    reportPluginError('PlayVideo', err.message || '동영상 재생을 시작할 수 없습니다.', err);
  }
}

export function initPlaybackEventListeners() {
  // 10초 주기 재생 진행률 동기화
  window.addEventListener('unraidhub:playerProgress', (event) => {
    const detail = (event && event.detail) || {};
    const session = state.activePlaybackSession;
    if (!session) return;
    api.reportPlaybackProgress(session, detail.positionSec || 0, false).catch((err) => {
      if (window.UnraidHub && window.UnraidHub.logError) {
        window.UnraidHub.logError('PlaybackSync', '재생 진행률 동기화 실패', err.detail || err.message);
      }
    });
  });

  // 플레이어 종료 / 완료 이벤트 처리 + 다음 화 5초 자동 재생 카운트다운
  window.addEventListener('unraidhub:playerClosed', async (event) => {
    const detail = (event && event.detail) || {};
    const session = state.activePlaybackSession;
    const ctx = state.activePlaybackContext;
    state.activePlaybackSession = null;
    state.activePlaybackContext = null;

    const finalPosSec = detail.completed && detail.durationSec > 0
      ? detail.durationSec
      : (detail.positionSec || 0);

    if (session) {
      try {
        await api.reportPlaybackStopped(session, finalPosSec);
      } catch (err) {
        if (window.UnraidHub && window.UnraidHub.logError) {
          window.UnraidHub.logError('PlaybackSync', '재생 종료 동기화 실패', err.detail || err.message);
        }
      }
    }

    // 홈 이어보기/다음 화 및 현재 화면 시청 상태 즉시 갱신
    await onAfterPlaybackRefresh();

    // 에피소드 시청 완료 + '다음 화 자동 재생' 설정 ON -> 5초 카운트다운 오버레이
    const nextEp = ctx && ctx.nextEp;
    if (
      detail.completed === true &&
      ctx &&
      ctx.item &&
      ctx.item.Type === 'Episode' &&
      nextEp &&
      state.prefs.autoPlayNext
    ) {
      showNextEpisodeCountdown(nextEp, {
        onPlayNow: () => {
          const top = currentView();
          if (top && top.type === 'detail') {
            top.itemId = nextEp.Id;
            top.item = nextEp;
            top.siblingEpisodes = Array.isArray(ctx.siblings) ? ctx.siblings : top.siblingEpisodes;
            top.selectedMediaSourceId = '';
            top.selectedAudioIndex = null;
            top.selectedSubValue = 'auto';
            triggerRender();
          }
          playEmbyItem(nextEp, { resume: false, knownSiblings: ctx.siblings });
        },
        onCancel: () => {
          showToast('다음 화 자동 재생을 취소했습니다.');
        }
      });
    }
  });

  // 플레이어 내부 [이전화] / [다음화] 버튼 클릭 시 플레이어 닫지 않고 즉시 전환
  window.addEventListener('unraidhub:playerAction', async (event) => {
    const detail = (event && event.detail) || {};
    const action = detail.action;
    if (action !== 'next' && action !== 'previous') return;

    const prevSession = state.activePlaybackSession;
    const ctx = state.activePlaybackContext;
    const targetEp = ctx ? (action === 'next' ? ctx.nextEp : ctx.prevEp) : null;

    if (prevSession) {
      api.reportPlaybackStopped(prevSession, detail.positionSec || 0).catch((err) => {
        if (window.UnraidHub && window.UnraidHub.logError) {
          window.UnraidHub.logError('PlaybackSync', '에피소드 전환 시 이전 화 종료 보고 실패', err.detail || err.message);
        }
      });
    }

    if (!targetEp) {
      showToast(action === 'next' ? '다음 에피소드가 없습니다.' : '이전 에피소드가 없습니다.');
      return;
    }

    const top = currentView();
    if (top && top.type === 'detail') {
      top.itemId = targetEp.Id;
      top.item = targetEp;
      top.siblingEpisodes = (ctx && Array.isArray(ctx.siblings)) ? ctx.siblings : top.siblingEpisodes;
      top.selectedMediaSourceId = '';
      top.selectedAudioIndex = null;
      top.selectedSubValue = 'auto';
      triggerRender();
    }

    await playEmbyItem(targetEp, { resume: true, knownSiblings: ctx ? ctx.siblings : null });
  });
}
