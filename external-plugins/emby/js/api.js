/**
 * Emby REST API 풀 기능 클라이언트 (window.UnraidHub.httpRequest 전용)
 * - 모든 HTTP 요청은 메인 앱의 OkHttpClient(tsnet 프록시 + 자체 서명 SSL 허용)를 경유합니다.
 */
import {
  TICKS_PER_SECOND,
  normalizeBaseUrl,
  formatEpisodeCode,
  matchesPreferredLang
} from './utils.js';

const DETAIL_FIELDS = [
  'Overview',
  'MediaSources',
  'MediaStreams',
  'UserData',
  'Genres',
  'Tags',
  'Taglines',
  'Studios',
  'People',
  'Chapters',
  'Path',
  'CanDelete',
  'CanDownload',
  'DateCreated',
  'PremiereDate',
  'ProductionYear',
  'OfficialRating',
  'CommunityRating',
  'CriticRating',
  'RunTimeTicks',
  'SeriesId',
  'SeriesName',
  'SeasonId',
  'SeasonName',
  'ParentIndexNumber',
  'IndexNumber',
  'OriginalTitle',
  'SortName',
  'ForcedSortName',
  'LockData',
  'LockedFields',
  'ProviderIds',
  'Container',
  'MediaType',
  'ChildCount',
  'RecursiveItemCount'
].join(',');

const LIST_FIELDS = [
  'PrimaryImageAspectRatio',
  'Overview',
  'UserData',
  'ProductionYear',
  'CommunityRating',
  'DateCreated',
  'PremiereDate',
  'RunTimeTicks',
  'SeriesId',
  'SeriesName',
  'SeasonId',
  'ParentIndexNumber',
  'IndexNumber',
  'ChildCount',
  'RecursiveItemCount',
  'CanDelete'
].join(',');

class EmbyApiClient {
  constructor() {
    this.baseUrl = '';
    this.accessToken = '';
    this.userId = '';
    this.deviceId = 'unraidhub-android';
  }

  configure({ baseUrl, accessToken, userId, deviceId }) {
    if (baseUrl !== undefined) this.baseUrl = normalizeBaseUrl(baseUrl);
    if (accessToken !== undefined) this.accessToken = String(accessToken || '');
    if (userId !== undefined) this.userId = String(userId || '');
    if (deviceId) this.deviceId = String(deviceId);
  }

  buildAuthHeader(includeToken = true) {
    const parts = [
      'Client="UnraidHub"',
      'Device="Android"',
      `DeviceId="${this.deviceId}"`,
      'Version="2.0.0"'
    ];
    if (includeToken && this.accessToken) {
      parts.push(`Token="${this.accessToken}"`);
    }
    return `MediaBrowser ${parts.join(', ')}`;
  }

  async request(path, options = {}) {
    const targetBase = normalizeBaseUrl(options.baseUrl || this.baseUrl);
    if (!targetBase) {
      throw new Error('Emby 서버 주소가 설정되지 않았습니다.');
    }

    const method = (options.method || 'GET').toUpperCase();
    const url = targetBase + (path.startsWith('/') ? path : '/' + path);
    const headers = {
      Accept: 'application/json',
      'X-Emby-Authorization': this.buildAuthHeader(options.withToken !== false),
      ...(options.headers || {})
    };

    if (options.withToken !== false && this.accessToken) {
      headers['X-Emby-Token'] = this.accessToken;
    }

    let body = null;
    if (options.body !== undefined && options.body !== null) {
      if (!headers['Content-Type']) {
        headers['Content-Type'] = 'application/json';
      }
      body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body);
    }

    const res = await window.UnraidHub.httpRequest({
      url,
      method,
      headers,
      body
    });

    if (!res.ok) {
      let serverMsg = '';
      try {
        const parsed = JSON.parse(res.body || '{}');
        serverMsg = parsed.Message || parsed.message || '';
      } catch (_) {
        serverMsg = (res.body || '').slice(0, 160);
      }
      const err = new Error(
        serverMsg
          ? `Emby 요청 실패 (HTTP ${res.status}): ${serverMsg}`
          : `Emby 서버 응답 오류 (HTTP ${res.status})`
      );
      err.status = res.status;
      err.isAuthError = res.status === 401;
      err.detail = `${method} ${url}\nHTTP ${res.status}\n${(res.body || '').slice(0, 400)}`;
      throw err;
    }

    if (res.status === 204 || !res.body || !res.body.trim()) {
      return null;
    }

    try {
      return JSON.parse(res.body);
    } catch (_) {
      return res.body;
    }
  }

  // =========================================================================
  // 인증 및 사용자
  // =========================================================================

  async authenticateByName(serverUrl, username, password) {
    const cleanBase = normalizeBaseUrl(serverUrl);
    if (!cleanBase) {
      throw new Error('Emby 서버 주소를 입력해 주세요.');
    }
    const data = await this.request('/Users/AuthenticateByName', {
      baseUrl: cleanBase,
      method: 'POST',
      withToken: false,
      body: {
        Username: String(username || '').trim(),
        Pw: String(password || '')
      }
    });

    const accessToken = data && data.AccessToken;
    const userId = data && data.User && data.User.Id;
    if (!accessToken || !userId) {
      throw new Error('로그인 응답에서 인증 토큰 또는 사용자 ID를 찾을 수 없습니다.');
    }

    this.configure({ baseUrl: cleanBase, accessToken, userId });

    return {
      baseUrl: cleanBase,
      accessToken,
      userId,
      userName: (data.User && data.User.Name) || username,
      serverId: data.ServerId || '',
      user: data.User || null
    };
  }

  async getCurrentUser() {
    if (!this.userId) return null;
    return this.request(`/Users/${encodeURIComponent(this.userId)}`);
  }

  async getPublicUsers(serverUrl) {
    const cleanBase = normalizeBaseUrl(serverUrl || this.baseUrl);
    if (!cleanBase) return [];
    const data = await this.request('/Users/Public', {
      baseUrl: cleanBase,
      withToken: false
    });
    return Array.isArray(data) ? data : [];
  }

  // =========================================================================
  // 홈 화면 데이터 (이어보기, 다음 화, 라이브러리, 최근 추가, 즐겨찾기, 컬렉션, 재생목록)
  // =========================================================================

  async getResumeItems(limit = 20) {
    const qs = new URLSearchParams({
      Recursive: 'true',
      Limit: String(limit),
      MediaTypes: 'Video',
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: `${LIST_FIELDS},MediaSources`
    });
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items/Resume?${qs}`);
    return (data && data.Items) || [];
  }

  async getNextUpItems(limit = 20) {
    const qs = new URLSearchParams({
      UserId: this.userId,
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: `${LIST_FIELDS},MediaSources`
    });
    const data = await this.request(`/Shows/NextUp?${qs}`);
    return (data && data.Items) || [];
  }

  async getLibraries() {
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Views`);
    return (data && data.Items) || [];
  }

  async getLatestItems(parentId = '', limit = 24) {
    const qs = new URLSearchParams({
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    if (parentId) qs.set('ParentId', parentId);
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items/Latest?${qs}`);
    return Array.isArray(data) ? data : (data && data.Items) || [];
  }

  async getFavoriteItems(limit = 24) {
    const qs = new URLSearchParams({
      Recursive: 'true',
      Filters: 'IsFavorite',
      IncludeItemTypes: 'Movie,Series,Episode,Video,BoxSet,Playlist',
      SortBy: 'SortName',
      SortOrder: 'Ascending',
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
    return (data && data.Items) || [];
  }

  async getCollections(limit = 30) {
    const qs = new URLSearchParams({
      Recursive: 'true',
      IncludeItemTypes: 'BoxSet',
      SortBy: 'SortName',
      SortOrder: 'Ascending',
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
    return (data && data.Items) || [];
  }

  async getPlaylists(limit = 40) {
    const qs = new URLSearchParams({
      Recursive: 'true',
      IncludeItemTypes: 'Playlist',
      SortBy: 'SortName',
      SortOrder: 'Ascending',
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
    return (data && data.Items) || [];
  }

  // =========================================================================
  // 라이브러리 탐색 · 정렬 · 필터 · 페이지네이션 · 검색 · 인물
  // =========================================================================

  async getLibraryItems({
    library,
    sortBy = 'name',
    sortOrder = 'Ascending',
    filterStatus = 'all',
    genreIds = [],
    years = [],
    startIndex = 0,
    limit = 48
  }) {
    const sortByMap = {
      name: 'SortName',
      date_added: 'DateCreated,SortName',
      release: 'PremiereDate,ProductionYear,SortName',
      rating: 'CommunityRating,SortName',
      random: 'Random'
    };
    const apiSortBy = sortByMap[sortBy] || 'SortName';
    const colType = String((library && library.CollectionType) || '').toLowerCase();

    const qs = new URLSearchParams({
      SortBy: apiSortBy,
      SortOrder: sortOrder === 'Descending' ? 'Descending' : 'Ascending',
      StartIndex: String(startIndex),
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });

    if (library && library.Id) {
      qs.set('ParentId', library.Id);
    }

    if (library && library.Type === 'BoxSet') {
      qs.set('Recursive', 'false');
    } else if (colType === 'tvshows') {
      qs.set('Recursive', 'true');
      qs.set('IncludeItemTypes', 'Series');
    } else if (colType === 'movies') {
      qs.set('Recursive', 'true');
      qs.set('IncludeItemTypes', 'Movie');
    } else if (colType === 'boxsets') {
      qs.set('Recursive', 'true');
      qs.set('IncludeItemTypes', 'BoxSet');
    } else if (colType === 'playlists') {
      qs.set('Recursive', 'true');
      qs.set('IncludeItemTypes', 'Playlist');
    } else if (colType === 'homevideos') {
      qs.set('Recursive', 'true');
      qs.set('IncludeItemTypes', 'Video,Movie');
    } else {
      // 필터가 적용된 경우 하위 폴더까지 검색
      const hasFilter = filterStatus !== 'all' || genreIds.length > 0 || years.length > 0;
      qs.set('Recursive', hasFilter ? 'true' : 'false');
    }

    const filters = [];
    if (filterStatus === 'unplayed') filters.push('IsUnplayed');
    else if (filterStatus === 'played') filters.push('IsPlayed');
    else if (filterStatus === 'favorite') filters.push('IsFavorite');
    if (filters.length > 0) qs.set('Filters', filters.join(','));

    if (genreIds.length > 0) qs.set('GenreIds', genreIds.join(','));
    if (years.length > 0) qs.set('Years', years.join(','));

    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
    return {
      items: (data && data.Items) || [],
      totalRecordCount: (data && typeof data.TotalRecordCount === 'number')
        ? data.TotalRecordCount
        : ((data && data.Items && data.Items.length) || 0)
    };
  }

  async getGenres(parentId = '') {
    const qs = new URLSearchParams({
      UserId: this.userId,
      SortBy: 'SortName',
      SortOrder: 'Ascending',
      Recursive: 'true'
    });
    if (parentId) qs.set('ParentId', parentId);
    const data = await this.request(`/Genres?${qs}`);
    return (data && data.Items) || [];
  }

  async getYears(parentId = '') {
    const qs = new URLSearchParams({
      UserId: this.userId,
      SortBy: 'SortName',
      SortOrder: 'Descending',
      Recursive: 'true'
    });
    if (parentId) qs.set('ParentId', parentId);
    const data = await this.request(`/Years?${qs}`);
    return (data && data.Items) || [];
  }

  async searchItems(query, limit = 50) {
    const q = String(query || '').trim();
    if (!q) return [];
    const qs = new URLSearchParams({
      SearchTerm: q,
      Recursive: 'true',
      IncludeItemTypes: 'Movie,Series,Episode,Video,BoxSet,Playlist,Person',
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
    return (data && data.Items) || [];
  }

  async getPersonDetail(personId) {
    const qs = new URLSearchParams({ Fields: 'Overview,PremiereDate,ProductionLocations' });
    return this.request(`/Users/${encodeURIComponent(this.userId)}/Items/${encodeURIComponent(personId)}?${qs}`);
  }

  async getPersonItems(personId, startIndex = 0, limit = 48) {
    const qs = new URLSearchParams({
      PersonIds: personId,
      Recursive: 'true',
      IncludeItemTypes: 'Movie,Series,Episode,Video',
      SortBy: 'PremiereDate,ProductionYear,SortName',
      SortOrder: 'Descending',
      StartIndex: String(startIndex),
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
    return {
      items: (data && data.Items) || [],
      totalRecordCount: (data && typeof data.TotalRecordCount === 'number')
        ? data.TotalRecordCount
        : ((data && data.Items && data.Items.length) || 0)
    };
  }

  // =========================================================================
  // 상세 · 시리즈 · 시즌 · 에피소드 · 비슷한 콘텐츠
  // =========================================================================

  async getItemDetail(itemId) {
    const qs = new URLSearchParams({ Fields: DETAIL_FIELDS });
    return this.request(`/Users/${encodeURIComponent(this.userId)}/Items/${encodeURIComponent(itemId)}?${qs}`);
  }

  async getRawItemForEdit(itemId) {
    // 메타데이터 저장(POST /Items/{Id}) 시 필드 유실 방지를 위해 전체 DTO 조회
    return this.request(`/Users/${encodeURIComponent(this.userId)}/Items/${encodeURIComponent(itemId)}`);
  }

  async getSeasons(seriesId) {
    const qs = new URLSearchParams({
      UserId: this.userId,
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: 'PrimaryImageAspectRatio,UserData,Overview'
    });
    const data = await this.request(`/Shows/${encodeURIComponent(seriesId)}/Seasons?${qs}`);
    return (data && data.Items) || [];
  }

  async getEpisodes(seriesId, seasonId = '') {
    const qs = new URLSearchParams({
      UserId: this.userId,
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: 'Overview,MediaSources,MediaStreams,UserData,RunTimeTicks,SeriesId,SeriesName,SeasonId,ParentIndexNumber,IndexNumber,CanDelete'
    });
    if (seasonId) qs.set('SeasonId', seasonId);
    const data = await this.request(`/Shows/${encodeURIComponent(seriesId)}/Episodes?${qs}`);
    return (data && data.Items) || [];
  }

  async getSimilarItems(itemId, limit = 16) {
    const qs = new URLSearchParams({
      UserId: this.userId,
      Limit: String(limit),
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: LIST_FIELDS
    });
    const data = await this.request(`/Items/${encodeURIComponent(itemId)}/Similar?${qs}`);
    return (data && data.Items) || [];
  }

  // =========================================================================
  // 사용자 상호작용 (시청 완료 · 즐겨찾기 · 재생목록)
  // =========================================================================

  async setPlayed(itemId, played) {
    const path = `/Users/${encodeURIComponent(this.userId)}/PlayedItems/${encodeURIComponent(itemId)}`;
    return this.request(path, { method: played ? 'POST' : 'DELETE' });
  }

  async setFavorite(itemId, isFavorite) {
    const path = `/Users/${encodeURIComponent(this.userId)}/FavoriteItems/${encodeURIComponent(itemId)}`;
    return this.request(path, { method: isFavorite ? 'POST' : 'DELETE' });
  }

  async getPlaylistItems(playlistId) {
    const qs = new URLSearchParams({
      UserId: this.userId,
      ImageTypeLimit: '1',
      EnableImageTypes: 'Primary,Backdrop,Thumb',
      Fields: `${LIST_FIELDS},MediaSources`
    });
    const data = await this.request(`/Playlists/${encodeURIComponent(playlistId)}/Items?${qs}`);
    return (data && data.Items) || [];
  }

  async createPlaylist(name, itemIds = []) {
    const cleanName = String(name || '').trim();
    if (!cleanName) throw new Error('재생목록 이름을 입력해 주세요.');
    const qs = new URLSearchParams({
      Name: cleanName,
      UserId: this.userId
    });
    if (itemIds.length > 0) qs.set('Ids', itemIds.join(','));
    return this.request(`/Playlists?${qs}`, {
      method: 'POST',
      body: {
        Name: cleanName,
        Ids: itemIds,
        UserId: this.userId
      }
    });
  }

  async addToPlaylist(playlistId, itemIds) {
    const ids = Array.isArray(itemIds) ? itemIds.filter(Boolean) : [itemIds].filter(Boolean);
    if (ids.length === 0) return null;
    const qs = new URLSearchParams({
      Ids: ids.join(','),
      UserId: this.userId
    });
    return this.request(`/Playlists/${encodeURIComponent(playlistId)}/Items?${qs}`, {
      method: 'POST'
    });
  }

  async removeFromPlaylist(playlistId, entryIds) {
    const ids = Array.isArray(entryIds) ? entryIds.filter(Boolean) : [entryIds].filter(Boolean);
    if (ids.length === 0) return null;
    const qs = new URLSearchParams({
      EntryIds: ids.join(',')
    });
    return this.request(`/Playlists/${encodeURIComponent(playlistId)}/Items?${qs}`, {
      method: 'DELETE'
    });
  }

  async renamePlaylist(playlistId, newName) {
    const cleanName = String(newName || '').trim();
    if (!cleanName) throw new Error('새 이름을 입력해 주세요.');
    const raw = await this.getRawItemForEdit(playlistId);
    const updated = {
      ...raw,
      Name: cleanName
    };
    return this.updateItemMetadata(playlistId, updated);
  }

  // =========================================================================
  // 메타데이터 편집 · 새로고침 · 식별(Identify) · 이미지 관리 · 항목 삭제
  // =========================================================================

  async updateItemMetadata(itemId, fullItemDto) {
    return this.request(`/Items/${encodeURIComponent(itemId)}`, {
      method: 'POST',
      body: fullItemDto
    });
  }

  async refreshItemMetadata(itemId, { mode = 'missing', replaceAllImages = false } = {}) {
    let metadataRefreshMode = 'FullRefresh';
    let imageRefreshMode = 'FullRefresh';
    let replaceAllMetadata = false;

    if (mode === 'scan') {
      metadataRefreshMode = 'Default';
      imageRefreshMode = 'Default';
      replaceAllMetadata = false;
    } else if (mode === 'missing') {
      metadataRefreshMode = 'FullRefresh';
      imageRefreshMode = 'FullRefresh';
      replaceAllMetadata = false;
    } else if (mode === 'replace') {
      metadataRefreshMode = 'FullRefresh';
      imageRefreshMode = 'FullRefresh';
      replaceAllMetadata = true;
    }

    const qs = new URLSearchParams({
      Recursive: 'true',
      MetadataRefreshMode: metadataRefreshMode,
      ImageRefreshMode: imageRefreshMode,
      ReplaceAllMetadata: String(replaceAllMetadata),
      ReplaceAllImages: String(Boolean(replaceAllImages))
    });
    return this.request(`/Items/${encodeURIComponent(itemId)}/Refresh?${qs}`, {
      method: 'POST'
    });
  }

  resolveRemoteSearchType(itemType) {
    const map = {
      Movie: 'Movie',
      Series: 'Series',
      Season: 'Season',
      Episode: 'Episode',
      BoxSet: 'BoxSet',
      Person: 'Person',
      MusicAlbum: 'MusicAlbum',
      MusicArtist: 'MusicArtist',
      MusicVideo: 'MusicVideo',
      Book: 'Book',
      Trailer: 'Trailer'
    };
    return map[itemType] || 'Movie';
  }

  async remoteSearch(item, { name, year, tmdbId, imdbId }) {
    const searchType = this.resolveRemoteSearchType(item && item.Type);
    const providerIds = {};
    if (tmdbId && String(tmdbId).trim()) providerIds.Tmdb = String(tmdbId).trim();
    if (imdbId && String(imdbId).trim()) providerIds.Imdb = String(imdbId).trim();

    const searchInfo = {};
    if (name && String(name).trim()) searchInfo.Name = String(name).trim();
    const numYear = parseInt(String(year || '').trim(), 10);
    if (!Number.isNaN(numYear) && numYear > 1800) searchInfo.Year = numYear;
    if (Object.keys(providerIds).length > 0) searchInfo.ProviderIds = providerIds;

    const data = await this.request(`/Items/RemoteSearch/${encodeURIComponent(searchType)}`, {
      method: 'POST',
      body: {
        SearchInfo: searchInfo,
        ItemId: item && item.Id ? item.Id : undefined,
        IncludeDisabledProviders: true
      }
    });
    return Array.isArray(data) ? data : [];
  }

  async applyRemoteSearch(itemId, searchResult, replaceAllImages = true) {
    const qs = new URLSearchParams({
      ReplaceAllImages: String(Boolean(replaceAllImages))
    });
    return this.request(`/Items/RemoteSearch/Apply/${encodeURIComponent(itemId)}?${qs}`, {
      method: 'POST',
      body: searchResult
    });
  }

  async getItemImages(itemId) {
    const data = await this.request(`/Items/${encodeURIComponent(itemId)}/Images`);
    return Array.isArray(data) ? data : [];
  }

  async getRemoteImages(itemId, imageType = 'Primary', startIndex = 0, limit = 30) {
    const qs = new URLSearchParams({
      Type: imageType,
      StartIndex: String(startIndex),
      Limit: String(limit),
      IncludeAllLanguages: 'true'
    });
    const data = await this.request(`/Items/${encodeURIComponent(itemId)}/RemoteImages?${qs}`);
    return (data && Array.isArray(data.Images)) ? data.Images : [];
  }

  async downloadRemoteImage(itemId, { type, imageUrl, providerName }) {
    const qs = new URLSearchParams({
      Type: type || 'Primary',
      ImageUrl: imageUrl || ''
    });
    if (providerName) qs.set('ProviderName', providerName);
    return this.request(`/Items/${encodeURIComponent(itemId)}/RemoteImages/Download?${qs}`, {
      method: 'POST'
    });
  }

  async deleteItemImage(itemId, imageType, imageIndex = null) {
    const basePath = `/Items/${encodeURIComponent(itemId)}/Images/${encodeURIComponent(imageType)}`;
    const path = (imageIndex !== null && imageIndex !== undefined)
      ? `${basePath}/${encodeURIComponent(String(imageIndex))}`
      : basePath;
    return this.request(path, { method: 'DELETE' });
  }

  async deleteItem(itemId) {
    return this.request(`/Items/${encodeURIComponent(itemId)}`, {
      method: 'DELETE'
    });
  }

  // =========================================================================
  // 서버 관리자 대시보드 API
  // =========================================================================

  async refreshAllLibraries() {
    return this.request('/Library/Refresh', { method: 'POST' });
  }

  async refreshLibrary(libraryId) {
    return this.refreshItemMetadata(libraryId, { mode: 'scan', replaceAllImages: false });
  }

  async getActiveSessions() {
    const data = await this.request('/Sessions');
    return Array.isArray(data) ? data : [];
  }

  async getScheduledTasks() {
    const data = await this.request('/ScheduledTasks');
    return Array.isArray(data) ? data : [];
  }

  async startScheduledTask(taskId) {
    return this.request(`/ScheduledTasks/Running/${encodeURIComponent(taskId)}`, {
      method: 'POST'
    });
  }

  async stopScheduledTask(taskId) {
    return this.request(`/ScheduledTasks/Running/${encodeURIComponent(taskId)}`, {
      method: 'DELETE'
    });
  }

  async getSystemInfo() {
    return this.request('/System/Info');
  }

  async restartServer() {
    return this.request('/System/Restart', { method: 'POST' });
  }

  // =========================================================================
  // 이미지 URL 빌더 (네이티브 OkHttp 이미지 프록시 경유)
  // =========================================================================

  proxyUrl(rawUrl) {
    if (!rawUrl) return '';
    if (window.UnraidHub && typeof window.UnraidHub.proxyUrl === 'function') {
      return window.UnraidHub.proxyUrl(rawUrl);
    }
    return rawUrl;
  }

  buildImageUrl(itemId, imageType = 'Primary', maxWidth = 400, tag = '', imageIndex = null) {
    if (!this.baseUrl || !itemId) return '';
    const qs = new URLSearchParams({
      maxWidth: String(maxWidth),
      quality: '88'
    });
    if (this.accessToken) qs.set('api_key', this.accessToken);
    if (tag) qs.set('tag', tag);
    const typeSegment = (imageIndex !== null && imageIndex !== undefined)
      ? `${encodeURIComponent(imageType)}/${encodeURIComponent(String(imageIndex))}`
      : encodeURIComponent(imageType);
    const rawUrl = `${this.baseUrl}/Items/${encodeURIComponent(itemId)}/Images/${typeSegment}?${qs}`;
    return this.proxyUrl(rawUrl);
  }

  getPosterUrl(item, maxWidth = 400) {
    if (!item) return '';
    if (item.ImageTags && item.ImageTags.Primary) {
      return this.buildImageUrl(item.Id, 'Primary', maxWidth, item.ImageTags.Primary);
    }
    if (item.SeriesId && item.SeriesPrimaryImageTag) {
      return this.buildImageUrl(item.SeriesId, 'Primary', maxWidth, item.SeriesPrimaryImageTag);
    }
    if (item.ParentId && item.ParentPrimaryImageTag) {
      return this.buildImageUrl(item.ParentId, 'Primary', maxWidth, item.ParentPrimaryImageTag);
    }
    if (item.ImageTags && item.ImageTags.Thumb) {
      return this.buildImageUrl(item.Id, 'Thumb', maxWidth, item.ImageTags.Thumb);
    }
    if (item.BackdropImageTags && item.BackdropImageTags.length > 0) {
      return this.buildImageUrl(item.Id, 'Backdrop', maxWidth, item.BackdropImageTags[0]);
    }
    if (item.ParentBackdropItemId && item.ParentBackdropImageTags && item.ParentBackdropImageTags.length > 0) {
      return this.buildImageUrl(item.ParentBackdropItemId, 'Backdrop', maxWidth, item.ParentBackdropImageTags[0]);
    }
    return this.buildImageUrl(item.Id, 'Primary', maxWidth);
  }

  getLandscapeImageUrl(item, maxWidth = 600) {
    if (!item) return '';
    if (item.ImageTags && item.ImageTags.Thumb) {
      return this.buildImageUrl(item.Id, 'Thumb', maxWidth, item.ImageTags.Thumb);
    }
    if (item.Type === 'Episode' && item.ImageTags && item.ImageTags.Primary) {
      return this.buildImageUrl(item.Id, 'Primary', maxWidth, item.ImageTags.Primary);
    }
    if (item.ParentThumbItemId && item.ParentThumbImageTag) {
      return this.buildImageUrl(item.ParentThumbItemId, 'Thumb', maxWidth, item.ParentThumbImageTag);
    }
    if (item.BackdropImageTags && item.BackdropImageTags.length > 0) {
      return this.buildImageUrl(item.Id, 'Backdrop', maxWidth, item.BackdropImageTags[0]);
    }
    if (item.ParentBackdropItemId && item.ParentBackdropImageTags && item.ParentBackdropImageTags.length > 0) {
      return this.buildImageUrl(item.ParentBackdropItemId, 'Backdrop', maxWidth, item.ParentBackdropImageTags[0]);
    }
    return this.getPosterUrl(item, maxWidth);
  }

  getBackdropUrl(item, maxWidth = 960) {
    if (!item) return '';
    if (item.BackdropImageTags && item.BackdropImageTags.length > 0) {
      return this.buildImageUrl(item.Id, 'Backdrop', maxWidth, item.BackdropImageTags[0]);
    }
    if (item.ParentBackdropItemId && item.ParentBackdropImageTags && item.ParentBackdropImageTags.length > 0) {
      return this.buildImageUrl(item.ParentBackdropItemId, 'Backdrop', maxWidth, item.ParentBackdropImageTags[0]);
    }
    if (item.SeriesId) {
      return this.buildImageUrl(item.SeriesId, 'Backdrop', maxWidth);
    }
    return this.getLandscapeImageUrl(item, maxWidth);
  }

  getLogoUrl(item, maxWidth = 480) {
    if (!item) return '';
    if (item.ImageTags && item.ImageTags.Logo) {
      return this.buildImageUrl(item.Id, 'Logo', maxWidth, item.ImageTags.Logo);
    }
    if (item.ParentLogoItemId && item.ParentLogoImageTag) {
      return this.buildImageUrl(item.ParentLogoItemId, 'Logo', maxWidth, item.ParentLogoImageTag);
    }
    return '';
  }

  getPersonImageUrl(person, maxWidth = 240) {
    if (!person || !person.Id) return '';
    return this.buildImageUrl(person.Id, 'Primary', maxWidth, person.PrimaryImageTag || (person.ImageTags && person.ImageTags.Primary) || '');
  }

  getChapterImageUrl(itemId, chapterIndex, imageTag = '', maxWidth = 360) {
    if (!this.baseUrl || !itemId || !imageTag) return '';
    return this.buildImageUrl(itemId, 'Chapter', maxWidth, imageTag, chapterIndex);
  }

  // =========================================================================
  // 내장 libmpv Direct Play 요청 및 재생 세션 동기화
  // =========================================================================

  buildDirectPlayRequest(item, {
    resume = true,
    startPositionSecOverride = null,
    mediaSourceId = '',
    audioStreamIndex = null,
    subtitleSelection = 'auto', // 'auto' | 'none' | number (Emby stream Index)
    preferredSubLang = 'kor',
    navInfo = null
  } = {}) {
    const mediaSources = Array.isArray(item.MediaSources) ? item.MediaSources : [];
    const mediaSource = (mediaSourceId && mediaSources.find((ms) => ms.Id === mediaSourceId))
      || mediaSources[0]
      || null;
    const resolvedSourceId = (mediaSource && mediaSource.Id) || item.Id;
    const playSessionId = `uh_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

    const streamQs = new URLSearchParams({
      static: 'true',
      MediaSourceId: resolvedSourceId
    });
    if (this.accessToken) streamQs.set('api_key', this.accessToken);
    const streamUrl = `${this.baseUrl}/Videos/${encodeURIComponent(item.Id)}/stream?${streamQs}`;

    const streams = (mediaSource && Array.isArray(mediaSource.MediaStreams))
      ? mediaSource.MediaStreams
      : (Array.isArray(item.MediaStreams) ? item.MediaStreams : []);

    const mapSubtitleExt = (rawCodec) => {
      const codec = String(rawCodec || 'srt').toLowerCase().replace(/[^a-z0-9]/g, '') || 'srt';
      if (codec === 'subrip' || codec === 'movtext' || codec === 'tx3g' || codec === 'text') return 'srt';
      if (codec === 'sami' || codec === 'smi') return 'smi';
      if (codec === 'webvtt' || codec === 'vtt') return 'vtt';
      return codec;
    };

    const externalSubStreams = streams.filter((s) => s && s.Type === 'Subtitle' && s.IsExternal);
    const chosenSubIdx = (typeof subtitleSelection === 'number') ? subtitleSelection : null;
    const disableExternalSubs = subtitleSelection === 'none' || preferredSubLang === 'none';

    let orderedExternals = [];
    if (!disableExternalSubs) {
      orderedExternals = [...externalSubStreams].sort((a, b) => {
        // 1순위: 사용자가 상세 화면에서 명시적으로 선택한 외부 자막 트랙
        if (chosenSubIdx !== null) {
          if (a.Index === chosenSubIdx) return -1;
          if (b.Index === chosenSubIdx) return 1;
        }
        // 2순위: 사용자 선호 자막 언어
        const aPref = Number(matchesPreferredLang(a, preferredSubLang));
        const bPref = Number(matchesPreferredLang(b, preferredSubLang));
        if (aPref !== bPref) return bPref - aPref;
        // 3순위: 한국어 우선 폴백
        const aKor = Number(matchesPreferredLang(a, 'kor'));
        const bKor = Number(matchesPreferredLang(b, 'kor'));
        return bKor - aKor;
      });
    }

    const subtitles = orderedExternals
      .map((s) => {
        let subUrl = '';
        if (s.DeliveryUrl) {
          let abs = /^https?:\/\//i.test(s.DeliveryUrl)
            ? s.DeliveryUrl
            : `${this.baseUrl}${s.DeliveryUrl.startsWith('/') ? '' : '/'}${s.DeliveryUrl}`;
          abs = abs.replace(/\/Stream\.sami(?=$|\?)/i, '/Stream.smi');
          subUrl = abs.includes('api_key=') || !this.accessToken
            ? abs
            : `${abs}${abs.includes('?') ? '&' : '?'}api_key=${encodeURIComponent(this.accessToken)}`;
        } else {
          const ext = mapSubtitleExt(s.Codec);
          const subQs = this.accessToken ? `?api_key=${encodeURIComponent(this.accessToken)}` : '';
          subUrl = `${this.baseUrl}/Videos/${encodeURIComponent(item.Id)}/${encodeURIComponent(resolvedSourceId)}/Subtitles/${s.Index}/Stream.${ext}${subQs}`;
        }

        return {
          url: subUrl,
          title: s.DisplayTitle || s.Title || s.Language || `외부 자막 #${s.Index}`,
          language: s.Language || 'und'
        };
      })
      .filter((sub) => Boolean(sub.url));

    let startPositionSec = 0;
    if (typeof startPositionSecOverride === 'number' && startPositionSecOverride >= 0) {
      startPositionSec = Math.floor(startPositionSecOverride);
    } else if (resume) {
      const resumeTicks = (item.UserData && item.UserData.PlaybackPositionTicks) || 0;
      if (resumeTicks > 0) startPositionSec = Math.floor(resumeTicks / TICKS_PER_SECOND);
    }

    const isEpisode = item.Type === 'Episode';
    const epCode = isEpisode ? formatEpisodeCode(item) : '';
    const title = isEpisode
      ? [epCode, item.Name].filter(Boolean).join(' · ')
      : (item.Name || '동영상');
    const subtitle = isEpisode
      ? (item.SeriesName || '')
      : (item.ProductionYear ? `${item.ProductionYear}년` : '');

    return {
      session: {
        itemId: item.Id,
        mediaSourceId: resolvedSourceId,
        playSessionId,
        audioStreamIndex: typeof audioStreamIndex === 'number' ? audioStreamIndex : undefined,
        subtitleStreamIndex: chosenSubIdx !== null ? chosenSubIdx : (subtitleSelection === 'none' ? -1 : undefined)
      },
      playVideoOptions: {
        url: streamUrl,
        title,
        subtitle,
        startPositionSec,
        headers: this.accessToken ? { 'X-Emby-Token': this.accessToken } : {},
        subtitles,
        hasPrevious: Boolean(navInfo && navInfo.hasPrevious),
        hasNext: Boolean(navInfo && navInfo.hasNext),
        previousTitle: (navInfo && navInfo.previousTitle) || '',
        nextTitle: (navInfo && navInfo.nextTitle) || ''
      }
    };
  }

  async reportPlaybackStarted(session, positionSec = 0) {
    if (!session || !session.itemId) return;
    const payload = {
      ItemId: session.itemId,
      MediaSourceId: session.mediaSourceId,
      PlaySessionId: session.playSessionId,
      PlayMethod: 'DirectPlay',
      CanSeek: true,
      IsPaused: false,
      PositionTicks: Math.max(0, Math.floor(Number(positionSec || 0) * TICKS_PER_SECOND))
    };
    if (session.audioStreamIndex !== undefined) payload.AudioStreamIndex = session.audioStreamIndex;
    if (session.subtitleStreamIndex !== undefined) payload.SubtitleStreamIndex = session.subtitleStreamIndex;
    await this.request('/Sessions/Playing', { method: 'POST', body: payload });
  }

  async reportPlaybackProgress(session, positionSec = 0, isPaused = false) {
    if (!session || !session.itemId) return;
    const payload = {
      ItemId: session.itemId,
      MediaSourceId: session.mediaSourceId,
      PlaySessionId: session.playSessionId,
      PlayMethod: 'DirectPlay',
      CanSeek: true,
      IsPaused: Boolean(isPaused),
      EventName: 'TimeUpdate',
      PositionTicks: Math.max(0, Math.floor(Number(positionSec || 0) * TICKS_PER_SECOND))
    };
    if (session.audioStreamIndex !== undefined) payload.AudioStreamIndex = session.audioStreamIndex;
    if (session.subtitleStreamIndex !== undefined) payload.SubtitleStreamIndex = session.subtitleStreamIndex;
    await this.request('/Sessions/Playing/Progress', { method: 'POST', body: payload });
  }

  async reportPlaybackStopped(session, positionSec = 0) {
    if (!session || !session.itemId) return;
    await this.request('/Sessions/Playing/Stopped', {
      method: 'POST',
      body: {
        ItemId: session.itemId,
        MediaSourceId: session.mediaSourceId,
        PlaySessionId: session.playSessionId,
        PositionTicks: Math.max(0, Math.floor(Number(positionSec || 0) * TICKS_PER_SECOND))
      }
    });
  }
}

export const api = new EmbyApiClient();
