/**
 * Emby REST API 클라이언트 (window.UnraidHub.httpRequest 전용)
 * - 모든 API 호출은 메인 앱의 OkHttpClient(tsnet 프록시 + 자체 서명 SSL 허용)를 경유해
 *   CORS 및 사설 인증서 오류 없이 홈/외부 망 어디서나 동작합니다.
 */
(function (global) {
  'use strict';

  const TICKS_PER_SECOND = 10000000;

  function normalizeBaseUrl(rawUrl) {
    const trimmed = String(rawUrl || '').trim().replace(/\/+$/, '');
    if (!trimmed) return '';
    if (/^https?:\/\//i.test(trimmed)) return trimmed;
    return 'http://' + trimmed;
  }

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
        'Version="1.0.0"'
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

      const method = options.method || 'GET';
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
        headers['Content-Type'] = 'application/json';
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
          serverMsg = (res.body || '').slice(0, 140);
        }
        const err = new Error(
          serverMsg
            ? `Emby 요청 실패 (HTTP ${res.status}): ${serverMsg}`
            : `Emby 서버 응답 오류 (HTTP ${res.status})`
        );
        err.status = res.status;
        err.isAuthError = res.status === 401;
        err.detail = `${method} ${url}\nHTTP ${res.status}\n${(res.body || '').slice(0, 300)}`;
        throw err;
      }

      if (res.status === 204 || !res.body || !res.body.trim()) {
        return null;
      }

      try {
        return JSON.parse(res.body);
      } catch (e) {
        return res.body;
      }
    }

    /**
     * 아이디/비밀번호로 Emby 로그인 (POST /Users/AuthenticateByName)
     */
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

      this.configure({
        baseUrl: cleanBase,
        accessToken,
        userId
      });

      return {
        baseUrl: cleanBase,
        accessToken,
        userId,
        userName: (data.User && data.User.Name) || username,
        serverId: data.ServerId || ''
      };
    }

    /**
     * 이어보기 목록 (Resume)
     */
    async getResumeItems(limit = 20) {
      const qs = new URLSearchParams({
        Recursive: 'true',
        Limit: String(limit),
        MediaTypes: 'Video',
        ImageTypeLimit: '1',
        EnableImageTypes: 'Primary,Backdrop,Thumb',
        Fields: 'PrimaryImageAspectRatio,Overview,MediaSources,UserData,SeriesName,ParentIndexNumber,IndexNumber,RunTimeTicks'
      });
      const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items/Resume?${qs}`);
      return (data && data.Items) || [];
    }

    /**
     * 사용자 라이브러리 목록 (Views)
     */
    async getLibraries() {
      const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Views`);
      return (data && data.Items) || [];
    }

    /**
     * 최근 추가된 항목 (Latest)
     */
    async getLatestItems(parentId = '', limit = 20) {
      const qs = new URLSearchParams({
        Limit: String(limit),
        ImageTypeLimit: '1',
        EnableImageTypes: 'Primary,Backdrop,Thumb',
        Fields: 'PrimaryImageAspectRatio,Overview,UserData,ProductionYear,DateCreated,RunTimeTicks,SeriesName'
      });
      if (parentId) qs.set('ParentId', parentId);
      const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items/Latest?${qs}`);
      return Array.isArray(data) ? data : (data && data.Items) || [];
    }

    /**
     * 특정 라이브러리 내부 항목 조회 (정렬 지원)
     */
    async getLibraryItems(library, sortKey = 'name_asc', limit = 200) {
      const sortMap = {
        name_asc: { SortBy: 'SortName', SortOrder: 'Ascending' },
        name_desc: { SortBy: 'SortName', SortOrder: 'Descending' },
        date_added: { SortBy: 'DateCreated,SortName', SortOrder: 'Descending' },
        release_desc: { SortBy: 'PremiereDate,ProductionYear,SortName', SortOrder: 'Descending' },
        rating_desc: { SortBy: 'CommunityRating,SortName', SortOrder: 'Descending' }
      };
      const sortConf = sortMap[sortKey] || sortMap.name_asc;
      const colType = String((library && library.CollectionType) || '').toLowerCase();

      const qs = new URLSearchParams({
        ParentId: library.Id,
        SortBy: sortConf.SortBy,
        SortOrder: sortConf.SortOrder,
        Limit: String(limit),
        ImageTypeLimit: '1',
        EnableImageTypes: 'Primary,Backdrop,Thumb',
        Fields: 'PrimaryImageAspectRatio,Overview,UserData,ProductionYear,CommunityRating,DateCreated,RunTimeTicks'
      });

      if (colType === 'tvshows') {
        qs.set('Recursive', 'true');
        qs.set('IncludeItemTypes', 'Series');
      } else if (colType === 'movies') {
        qs.set('Recursive', 'true');
        qs.set('IncludeItemTypes', 'Movie');
      } else {
        qs.set('Recursive', 'false');
      }

      const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
      return (data && data.Items) || [];
    }

    /**
     * 실시간 제목 검색
     */
    async searchItems(query, limit = 40) {
      const q = String(query || '').trim();
      if (!q) return [];
      const qs = new URLSearchParams({
        SearchTerm: q,
        Recursive: 'true',
        IncludeItemTypes: 'Movie,Series,Episode,Video',
        Limit: String(limit),
        ImageTypeLimit: '1',
        EnableImageTypes: 'Primary,Backdrop,Thumb',
        Fields: 'PrimaryImageAspectRatio,Overview,UserData,ProductionYear,SeriesName,ParentIndexNumber,IndexNumber,RunTimeTicks'
      });
      const data = await this.request(`/Users/${encodeURIComponent(this.userId)}/Items?${qs}`);
      return (data && data.Items) || [];
    }

    /**
     * 단일 아이템 상세 정보 (MediaSources / MediaStreams 포함)
     */
    async getItemDetail(itemId) {
      const qs = new URLSearchParams({
        Fields: 'Overview,MediaSources,MediaStreams,UserData,Genres,ProductionYear,OfficialRating,CommunityRating,RunTimeTicks,SeriesId,SeriesName,SeasonId,SeasonName,ParentIndexNumber,IndexNumber,DateCreated'
      });
      return this.request(`/Users/${encodeURIComponent(this.userId)}/Items/${encodeURIComponent(itemId)}?${qs}`);
    }

    /**
     * 시리즈의 시즌 목록
     */
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

    /**
     * 시리즈/시즌의 에피소드 목록
     */
    async getEpisodes(seriesId, seasonId = '') {
      const qs = new URLSearchParams({
        UserId: this.userId,
        ImageTypeLimit: '1',
        EnableImageTypes: 'Primary,Backdrop,Thumb',
        Fields: 'Overview,MediaSources,MediaStreams,UserData,RunTimeTicks,SeriesId,SeriesName,SeasonId,ParentIndexNumber,IndexNumber'
      });
      if (seasonId) qs.set('SeasonId', seasonId);
      const data = await this.request(`/Shows/${encodeURIComponent(seriesId)}/Episodes?${qs}`);
      return (data && data.Items) || [];
    }

    /**
     * Emby 이미지 URL 생성 (네이티브 OkHttp 이미지 프록시 우선 경유로 Mixed Content / 사설 SSL / tsnet 문제 완전 해소)
     */
    buildImageUrl(itemId, imageType = 'Primary', maxWidth = 400, tag = '') {
      if (!this.baseUrl || !itemId) return '';
      const qs = new URLSearchParams({
        maxWidth: String(maxWidth),
        quality: '88'
      });
      if (this.accessToken) qs.set('api_key', this.accessToken);
      if (tag) qs.set('tag', tag);
      const rawUrl = `${this.baseUrl}/Items/${encodeURIComponent(itemId)}/Images/${encodeURIComponent(imageType)}?${qs}`;
      if (window.UnraidHub && typeof window.UnraidHub.proxyUrl === 'function') {
        return window.UnraidHub.proxyUrl(rawUrl);
      }
      return rawUrl;
    }

    /**
     * 아이템 포스터 URL 선택 (Primary 우선, 없으면 시리즈/상위/Thumb/Backdrop 순으로 폴백)
     */
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

    /**
     * 16:9 썸네일/배경 URL 선택 (에피소드/이어보기 카드용)
     */
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

    /**
     * 배경(Backdrop) URL 선택 (상세/시리즈 히어로용)
     */
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

    /**
     * 내장 libmpv Direct Play용 페이로드 및 세션 메타 생성
     */
    buildDirectPlayRequest(item, resume = true, navInfo = null) {
      const mediaSources = Array.isArray(item.MediaSources) ? item.MediaSources : [];
      const mediaSource = mediaSources[0] || null;
      const mediaSourceId = (mediaSource && mediaSource.Id) || item.Id;
      const playSessionId = `uh_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

      const streamQs = new URLSearchParams({
        static: 'true',
        MediaSourceId: mediaSourceId
      });
      if (this.accessToken) streamQs.set('api_key', this.accessToken);
      const streamUrl = `${this.baseUrl}/Videos/${encodeURIComponent(item.Id)}/stream?${streamQs}`;

      // 외부 자막 스트림 추출 (SAMI .smi 확장자 정규화 및 한국어 자막 우선 정렬)
      const streams = (mediaSource && Array.isArray(mediaSource.MediaStreams))
        ? mediaSource.MediaStreams
        : (Array.isArray(item.MediaStreams) ? item.MediaStreams : []);

      const isKoreanStream = (s) => {
        const lang = String(s.Language || '').toLowerCase();
        const title = String(s.DisplayTitle || s.Title || '').toLowerCase();
        return lang.startsWith('ko') || lang === 'kr' || lang === 'kor' ||
          title.includes('한국') || title.includes('한글') || title.includes('korean') || title.includes('kor');
      };

      const mapSubtitleExt = (rawCodec) => {
        const codec = String(rawCodec || 'srt').toLowerCase().replace(/[^a-z0-9]/g, '') || 'srt';
        if (codec === 'subrip' || codec === 'movtext' || codec === 'tx3g' || codec === 'text') return 'srt';
        if (codec === 'sami' || codec === 'smi') return 'smi';
        if (codec === 'webvtt' || codec === 'vtt') return 'vtt';
        return codec;
      };

      const subtitles = streams
        .filter((s) => s && s.Type === 'Subtitle' && s.IsExternal)
        .sort((a, b) => Number(isKoreanStream(b)) - Number(isKoreanStream(a)))
        .map((s) => {
          let subUrl = '';
          if (s.DeliveryUrl) {
            let abs = /^https?:\/\//i.test(s.DeliveryUrl)
              ? s.DeliveryUrl
              : `${this.baseUrl}${s.DeliveryUrl.startsWith('/') ? '' : '/'}${s.DeliveryUrl}`;
            // Emby가 /Stream.sami 로 내려줄 경우 원시 SAMI 다운로드 호환성이 높은 /Stream.smi 로 보정
            abs = abs.replace(/\/Stream\.sami(?=$|\?)/i, '/Stream.smi');
            subUrl = abs.includes('api_key=') || !this.accessToken
              ? abs
              : `${abs}${abs.includes('?') ? '&' : '?'}api_key=${encodeURIComponent(this.accessToken)}`;
          } else {
            const ext = mapSubtitleExt(s.Codec);
            const subQs = this.accessToken ? `?api_key=${encodeURIComponent(this.accessToken)}` : '';
            subUrl = `${this.baseUrl}/Videos/${encodeURIComponent(item.Id)}/${encodeURIComponent(mediaSourceId)}/Subtitles/${s.Index}/Stream.${ext}${subQs}`;
          }

          return {
            url: subUrl,
            title: s.DisplayTitle || s.Title || s.Language || `외부 자막 #${s.Index}`,
            language: s.Language || 'und'
          };
        })
        .filter((sub) => Boolean(sub.url));

      const resumeTicks = (item.UserData && item.UserData.PlaybackPositionTicks) || 0;
      const startPositionSec = resume && resumeTicks > 0
        ? Math.floor(resumeTicks / TICKS_PER_SECOND)
        : 0;

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
          mediaSourceId,
          playSessionId
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

    /**
     * Emby 재생 시작 보고 (/Sessions/Playing)
     */
    async reportPlaybackStarted(session, positionSec = 0) {
      if (!session || !session.itemId) return;
      await this.request('/Sessions/Playing', {
        method: 'POST',
        body: {
          ItemId: session.itemId,
          MediaSourceId: session.mediaSourceId,
          PlaySessionId: session.playSessionId,
          PlayMethod: 'DirectPlay',
          CanSeek: true,
          IsPaused: false,
          PositionTicks: Math.max(0, Math.floor(Number(positionSec || 0) * TICKS_PER_SECOND))
        }
      });
    }

    /**
     * Emby 재생 진행률 보고 (/Sessions/Playing/Progress)
     */
    async reportPlaybackProgress(session, positionSec = 0, isPaused = false) {
      if (!session || !session.itemId) return;
      await this.request('/Sessions/Playing/Progress', {
        method: 'POST',
        body: {
          ItemId: session.itemId,
          MediaSourceId: session.mediaSourceId,
          PlaySessionId: session.playSessionId,
          PlayMethod: 'DirectPlay',
          CanSeek: true,
          IsPaused: Boolean(isPaused),
          EventName: 'TimeUpdate',
          PositionTicks: Math.max(0, Math.floor(Number(positionSec || 0) * TICKS_PER_SECOND))
        }
      });
    }

    /**
     * Emby 재생 종료 보고 (/Sessions/Playing/Stopped)
     */
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

  function formatEpisodeCode(item) {
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

  global.EmbyApiClient = EmbyApiClient;
  global.EmbyUtils = {
    TICKS_PER_SECOND,
    normalizeBaseUrl,
    formatEpisodeCode
  };
})(window);
