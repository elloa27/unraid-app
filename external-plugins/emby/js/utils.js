/**
 * 공용 포맷팅·DOM 유틸리티
 */

export const TICKS_PER_SECOND = 10000000;

export function escapeHtml(str) {
  return String(str == null ? '' : str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function normalizeBaseUrl(rawUrl) {
  const trimmed = String(rawUrl || '').trim().replace(/\/+$/, '');
  if (!trimmed) return '';
  if (/^https?:\/\//i.test(trimmed)) return trimmed;
  return 'http://' + trimmed;
}

export function formatEpisodeCode(item) {
  const s = item && item.ParentIndexNumber;
  const e = item && item.IndexNumber;
  if (s != null && e != null) {
    return `S${String(s).padStart(2, '0')}E${String(e).padStart(2, '0')}`;
  }
  if (e != null) {
    return `E${String(e).padStart(2, '0')}`;
  }
  return '';
}

export function formatEpisodeNavLabel(ep) {
  if (!ep) return '';
  const code = formatEpisodeCode(ep);
  return [code, ep.Name].filter(Boolean).join(' · ') || ep.Name || '';
}

export function formatRuntime(ticks) {
  const totalMin = Math.round(Number(ticks || 0) / TICKS_PER_SECOND / 60);
  if (!totalMin || totalMin <= 0) return '';
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return h > 0 ? `${h}시간 ${m}분` : `${m}분`;
}

export function formatTimestamp(seconds) {
  const sec = Math.max(0, Math.floor(Number(seconds || 0)));
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export function formatFileSize(bytes) {
  const num = Number(bytes || 0);
  if (!num || num <= 0) return '-';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let idx = 0;
  let val = num;
  while (val >= 1024 && idx < units.length - 1) {
    val /= 1024;
    idx += 1;
  }
  return `${val >= 100 ? val.toFixed(0) : val >= 10 ? val.toFixed(1) : val.toFixed(2)} ${units[idx]}`;
}

export function formatBitrate(bps) {
  const num = Number(bps || 0);
  if (!num || num <= 0) return '-';
  if (num >= 1000000) return `${(num / 1000000).toFixed(2)} Mbps`;
  if (num >= 1000) return `${Math.round(num / 1000)} kbps`;
  return `${num} bps`;
}

export function formatDate(iso) {
  if (!iso) return '-';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return String(iso).slice(0, 10);
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  } catch (_) {
    return String(iso).slice(0, 10);
  }
}

export function formatDateTime(iso) {
  if (!iso) return '-';
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '-';
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${y}-${m}-${day} ${hh}:${mm}`;
  } catch (_) {
    return '-';
  }
}

export function toDateInputValue(iso) {
  if (!iso) return '';
  const str = String(iso);
  if (/^\d{4}-\d{2}-\d{2}/.test(str)) return str.slice(0, 10);
  const formatted = formatDate(iso);
  return formatted === '-' ? '' : formatted;
}

export function getProgressPercent(item) {
  const ud = item && item.UserData;
  if (!ud) return 0;
  if (typeof ud.PlayedPercentage === 'number' && ud.PlayedPercentage > 0) {
    return Math.min(100, Math.max(0, ud.PlayedPercentage));
  }
  if (ud.PlaybackPositionTicks && item.RunTimeTicks) {
    return Math.min(100, Math.max(0, (ud.PlaybackPositionTicks / item.RunTimeTicks) * 100));
  }
  return 0;
}

export function getCollectionTypeLabel(type) {
  const map = {
    movies: '영화',
    tvshows: '시리즈',
    music: '음악',
    homevideos: '비디오',
    boxsets: '컬렉션',
    playlists: '재생목록',
    mixed: '통합',
    books: '도서',
    livetv: '라이브 TV'
  };
  return map[String(type || '').toLowerCase()] || '라이브러리';
}

export function getItemTypeLabel(type) {
  const map = {
    Movie: '영화',
    Series: 'TV 시리즈',
    Season: '시즌',
    Episode: '에피소드',
    Video: '비디오',
    BoxSet: '컬렉션',
    Playlist: '재생목록',
    Person: '인물',
    Folder: '폴더',
    CollectionFolder: '라이브러리',
    MusicVideo: '뮤직비디오'
  };
  return map[type] || type || '미디어';
}

export function matchesPreferredLang(stream, prefCode) {
  if (!stream || !prefCode || prefCode === 'auto' || prefCode === 'none') return false;
  const lang = String(stream.Language || '').toLowerCase();
  const title = String(stream.DisplayTitle || stream.Title || '').toLowerCase();
  if (prefCode === 'kor') {
    return lang.startsWith('ko') || lang === 'kr' || lang === 'kor' ||
      title.includes('한국') || title.includes('한글') || title.includes('korean') || title.includes('kor');
  }
  if (prefCode === 'eng') {
    return lang.startsWith('en') || lang === 'eng' ||
      title.includes('영어') || title.includes('english') || title.includes('eng');
  }
  if (prefCode === 'jpn') {
    return lang.startsWith('ja') || lang === 'jp' || lang === 'jpn' ||
      title.includes('일본') || title.includes('japanese') || title.includes('jpn');
  }
  return lang === prefCode;
}

export function renderFallbackImage(url, alt, initialText, extraClass = '') {
  const initial = escapeHtml((initialText || alt || '?').trim().charAt(0).toUpperCase() || '?');
  const safeAlt = escapeHtml(alt || '');
  return `
    <div class="img-fallback">
      <span class="img-fallback-initial">${initial}</span>
      <span class="img-fallback-label">${safeAlt}</span>
    </div>
    ${url ? `<img src="${escapeHtml(url)}" alt="${safeAlt}" class="${extraClass}" loading="lazy" data-fallback-img="1" />` : ''}
  `;
}

export function bindFallbackImages(root) {
  if (!root) return;
  root.querySelectorAll('img[data-fallback-img]').forEach((img) => {
    const markLoaded = () => img.classList.add('is-loaded');
    const markFailed = () => {
      img.style.display = 'none';
    };

    if (img.complete) {
      if (img.naturalWidth > 0) markLoaded();
      else markFailed();
      return;
    }

    img.addEventListener('load', markLoaded, { once: true });
    img.addEventListener('error', markFailed, { once: true });
  });
}

/**
 * 터치 길게 누르기 + 우클릭(contextmenu) 감지 헬퍼
 * - 스크롤 이동(10px 초과) 시 취소되어 터치 스크롤을 방해하지 않습니다.
 * - 길게 누르기가 발동하면 직후 발생하는 click 이벤트를 1회 차단합니다.
 */
export function attachLongPress(container, selector, onLongPress) {
  if (!container) return;
  let timer = null;
  let startX = 0;
  let startY = 0;
  let activeEl = null;
  let triggeredAt = 0;

  const cancel = () => {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    activeEl = null;
  };

  container.addEventListener('touchstart', (e) => {
    const target = e.target.closest(selector);
    if (!target || e.touches.length !== 1) return;
    const t = e.touches[0];
    startX = t.clientX;
    startY = t.clientY;
    activeEl = target;
    timer = setTimeout(() => {
      triggeredAt = Date.now();
      const el = activeEl;
      cancel();
      if (el) {
        try {
          if (navigator.vibrate) navigator.vibrate(20);
        } catch (_) {}
        onLongPress(el);
      }
    }, 520);
  }, { passive: true });

  container.addEventListener('touchmove', (e) => {
    if (!timer || !e.touches.length) return;
    const t = e.touches[0];
    if (Math.abs(t.clientX - startX) > 12 || Math.abs(t.clientY - startY) > 12) {
      cancel();
    }
  }, { passive: true });

  container.addEventListener('touchend', cancel, { passive: true });
  container.addEventListener('touchcancel', cancel, { passive: true });

  container.addEventListener('contextmenu', (e) => {
    const target = e.target.closest(selector);
    if (!target) return;
    e.preventDefault();
    triggeredAt = Date.now();
    onLongPress(target);
  });

  // 길게 누르기 직후 발생하는 click 억제
  container.addEventListener('click', (e) => {
    if (Date.now() - triggeredAt < 450) {
      e.preventDefault();
      e.stopPropagation();
    }
  }, true);
}

export async function copyToClipboard(text) {
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch (_) {}
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch (_) {
    return false;
  }
}
