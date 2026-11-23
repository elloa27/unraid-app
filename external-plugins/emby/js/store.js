/**
 * 전역 상태, 라우팅 스택, 설정 저장소(UnraidHub.storage*) 관리 모듈
 */
import { normalizeBaseUrl } from './utils.js';

export const STORAGE_KEYS = {
  ACCESS_TOKEN: 'emby_access_token',
  USER_ID: 'emby_user_id',
  USER_NAME: 'emby_user_name',
  CUSTOM_SERVER_URL: 'emby_custom_server_url',
  DEVICE_ID: 'emby_device_id',
  LIBRARY_SORT_BY: 'emby_library_sort_by',
  LIBRARY_SORT_ORDER: 'emby_library_sort_order',
  PREF_AUTO_PLAY_NEXT: 'emby_pref_auto_play_next',
  PREF_AUDIO_LANG: 'emby_pref_audio_lang',
  PREF_SUB_LANG: 'emby_pref_sub_lang',
  PREF_GRID_COLS: 'emby_pref_grid_cols',
  SAVED_ACCOUNTS: 'emby_saved_accounts'
};

export const state = {
  context: {
    pluginId: 'emby',
    pluginVersion: '2.0.0',
    serviceName: 'Emby',
    serviceUrl: '',
    tailscaleUrl: '',
    activeBaseUrl: '',
    isTailscaleActive: false
  },
  customServerUrl: '',
  userName: '',
  user: null, // GET /Users/{Id} 응답 (Policy 포함)
  isAuthenticated: false,
  prefs: {
    autoPlayNext: true,
    preferredAudioLang: 'kor',
    preferredSubLang: 'kor',
    gridCols: 3,
    librarySortBy: 'name',
    librarySortOrder: 'Ascending'
  },
  savedAccounts: [],
  stack: [],
  activePlaybackSession: null,
  activePlaybackContext: null,
  homeData: {
    loading: false,
    error: '',
    resume: [],
    nextUp: [],
    libraries: [],
    latest: [],
    favorites: [],
    collections: [],
    playlists: []
  },
  searchTimer: null
};

let renderCallback = () => {};

export function setRenderHandler(fn) {
  renderCallback = typeof fn === 'function' ? fn : () => {};
}

export function triggerRender() {
  renderCallback();
}

export function isAdmin() {
  return Boolean(state.user && state.user.Policy && state.user.Policy.IsAdministrator);
}

export function canDeleteItem(item) {
  if (!item) return false;
  if (typeof item.CanDelete === 'boolean') return item.CanDelete;
  const policy = state.user && state.user.Policy;
  if (!policy) return false;
  return Boolean(policy.IsAdministrator || policy.EnableContentDeletion);
}

export function resolveActiveBaseUrl(customUrl = state.customServerUrl, ctx = state.context) {
  const cleanCustom = normalizeBaseUrl(customUrl);
  const cleanService = normalizeBaseUrl(ctx && ctx.serviceUrl);
  const cleanActive = normalizeBaseUrl(ctx && ctx.activeBaseUrl);

  if (cleanCustom) {
    if (ctx && ctx.isTailscaleActive && cleanService && cleanCustom === cleanService && cleanActive) {
      return cleanActive;
    }
    return cleanCustom;
  }
  return cleanActive || cleanService;
}

export function currentView() {
  return state.stack[state.stack.length - 1] || null;
}

export function pushView(view, replace = false) {
  if (replace && state.stack.length > 0) {
    state.stack[state.stack.length - 1] = view;
    try {
      history.replaceState({ depth: state.stack.length }, '', `#${view.type}`);
    } catch (_) {}
  } else {
    state.stack.push(view);
    try {
      if (state.stack.length === 1) {
        history.replaceState({ depth: 1 }, '', `#${view.type}`);
      } else {
        history.pushState({ depth: state.stack.length }, '', `#${view.type}`);
      }
    } catch (_) {}
  }
  triggerRender();
  window.scrollTo(0, 0);
}

export function popViewImmediate() {
  if (state.stack.length > 1) {
    state.stack.pop();
    triggerRender();
    return true;
  }
  return false;
}

export function navigateBack() {
  if (state.stack.length > 1) {
    history.back();
  } else if (window.UnraidHub && typeof window.UnraidHub.close === 'function') {
    window.UnraidHub.close();
  }
}

export function resetHomeData() {
  state.homeData = {
    loading: false,
    error: '',
    resume: [],
    nextUp: [],
    libraries: [],
    latest: [],
    favorites: [],
    collections: [],
    playlists: []
  };
}

export async function loadSavedSettings() {
  const [
    accessToken,
    userId,
    userName,
    customServerUrl,
    deviceIdRaw,
    sortBy,
    sortOrder,
    autoPlayNextRaw,
    audioLang,
    subLang,
    gridColsRaw,
    savedAccountsRaw
  ] = await Promise.all([
    window.UnraidHub.storageGet(STORAGE_KEYS.ACCESS_TOKEN),
    window.UnraidHub.storageGet(STORAGE_KEYS.USER_ID),
    window.UnraidHub.storageGet(STORAGE_KEYS.USER_NAME),
    window.UnraidHub.storageGet(STORAGE_KEYS.CUSTOM_SERVER_URL),
    window.UnraidHub.storageGet(STORAGE_KEYS.DEVICE_ID),
    window.UnraidHub.storageGet(STORAGE_KEYS.LIBRARY_SORT_BY),
    window.UnraidHub.storageGet(STORAGE_KEYS.LIBRARY_SORT_ORDER),
    window.UnraidHub.storageGet(STORAGE_KEYS.PREF_AUTO_PLAY_NEXT),
    window.UnraidHub.storageGet(STORAGE_KEYS.PREF_AUDIO_LANG),
    window.UnraidHub.storageGet(STORAGE_KEYS.PREF_SUB_LANG),
    window.UnraidHub.storageGet(STORAGE_KEYS.PREF_GRID_COLS),
    window.UnraidHub.storageGet(STORAGE_KEYS.SAVED_ACCOUNTS)
  ]);

  let deviceId = deviceIdRaw;
  if (!deviceId) {
    deviceId = `unraidhub_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
    await window.UnraidHub.storageSet(STORAGE_KEYS.DEVICE_ID, deviceId);
  }

  state.customServerUrl = customServerUrl || '';
  state.userName = userName || '';
  state.prefs.librarySortBy = sortBy || 'name';
  state.prefs.librarySortOrder = sortOrder === 'Descending' ? 'Descending' : 'Ascending';
  state.prefs.autoPlayNext = autoPlayNextRaw === null ? true : autoPlayNextRaw !== 'false';
  state.prefs.preferredAudioLang = audioLang || 'kor';
  state.prefs.preferredSubLang = subLang || 'kor';
  const parsedCols = parseInt(gridColsRaw || '3', 10);
  state.prefs.gridCols = (parsedCols >= 2 && parsedCols <= 4) ? parsedCols : 3;

  try {
    const parsedAccounts = savedAccountsRaw ? JSON.parse(savedAccountsRaw) : [];
    state.savedAccounts = Array.isArray(parsedAccounts) ? parsedAccounts : [];
  } catch (_) {
    state.savedAccounts = [];
  }

  return {
    accessToken: accessToken || '',
    userId: userId || '',
    userName: userName || '',
    customServerUrl: state.customServerUrl,
    deviceId
  };
}

export async function savePref(key, value) {
  const storageKeyMap = {
    autoPlayNext: STORAGE_KEYS.PREF_AUTO_PLAY_NEXT,
    preferredAudioLang: STORAGE_KEYS.PREF_AUDIO_LANG,
    preferredSubLang: STORAGE_KEYS.PREF_SUB_LANG,
    gridCols: STORAGE_KEYS.PREF_GRID_COLS,
    librarySortBy: STORAGE_KEYS.LIBRARY_SORT_BY,
    librarySortOrder: STORAGE_KEYS.LIBRARY_SORT_ORDER
  };
  state.prefs[key] = value;
  const storageKey = storageKeyMap[key];
  if (storageKey) {
    await window.UnraidHub.storageSet(storageKey, String(value));
  }
}

export async function saveAccountSession({ baseUrl, accessToken, userId, userName, isAdminFlag = false }) {
  await Promise.all([
    window.UnraidHub.storageSet(STORAGE_KEYS.ACCESS_TOKEN, accessToken),
    window.UnraidHub.storageSet(STORAGE_KEYS.USER_ID, userId),
    window.UnraidHub.storageSet(STORAGE_KEYS.USER_NAME, userName),
    window.UnraidHub.storageSet(STORAGE_KEYS.CUSTOM_SERVER_URL, baseUrl)
  ]);
  state.customServerUrl = baseUrl;
  state.userName = userName;
  state.isAuthenticated = true;

  // 계정 전환 목록에 기록 (동일 baseUrl+userId 덮어쓰기)
  const filtered = state.savedAccounts.filter(
    (acc) => !(acc.baseUrl === baseUrl && acc.userId === userId)
  );
  filtered.unshift({
    baseUrl,
    accessToken,
    userId,
    userName,
    isAdmin: Boolean(isAdminFlag),
    lastUsedAt: Date.now()
  });
  state.savedAccounts = filtered.slice(0, 8);
  await window.UnraidHub.storageSet(STORAGE_KEYS.SAVED_ACCOUNTS, JSON.stringify(state.savedAccounts));
}

export async function removeSavedAccount(baseUrl, userId) {
  state.savedAccounts = state.savedAccounts.filter(
    (acc) => !(acc.baseUrl === baseUrl && acc.userId === userId)
  );
  await window.UnraidHub.storageSet(STORAGE_KEYS.SAVED_ACCOUNTS, JSON.stringify(state.savedAccounts));
}

export async function clearActiveSession() {
  await Promise.all([
    window.UnraidHub.storageRemove(STORAGE_KEYS.ACCESS_TOKEN),
    window.UnraidHub.storageRemove(STORAGE_KEYS.USER_ID),
    window.UnraidHub.storageRemove(STORAGE_KEYS.USER_NAME)
  ]);
  state.isAuthenticated = false;
  state.userName = '';
  state.user = null;
  resetHomeData();
}
