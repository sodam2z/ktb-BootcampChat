import { useState, useCallback, useRef } from 'react';
import axiosInstance from '@/services/axios';
import { CONNECTION_STATUS } from './useServerConnection';

export const DEFAULT_PAGE_METADATA = {
  total: 0,
  page: 0,
  pageSize: 20,
  totalPages: 0,
  hasMore: false,
  currentCount: 0,
  sort: { field: 'createdAt', order: 'DESC' },
};

export const useRoomList = ({
  currentUser,
  router,
  connectionStatus,
  setConnectionStatus,
  isRetrying,
}) => {
  const [rooms, setRooms] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [isInitialLoad, setIsInitialLoad] = useState(true);
  const [joiningRoom, setJoiningRoom] = useState(false);
  const [pageLoading, setPageLoading] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);
  const [metadata, setMetadata] = useState(DEFAULT_PAGE_METADATA);

  const isLoadingRef = useRef(false);

  const handleFetchError = useCallback((error) => {
    let errorMessage = '채팅방 목록을 불러오는데 실패했습니다.';
    let errorType = 'danger';
    let showRetry = !isRetrying;

    if (error.message === 'AUTH_EXPIRED') {
      errorMessage = '인증이 만료되었습니다. 다시 로그인해주세요.';
      errorType = 'danger';
      showRetry = false;

      setError({
        title: '인증 만료',
        message: errorMessage,
        type: errorType,
        showRetry,
      });

      setConnectionStatus(CONNECTION_STATUS.ERROR);
      return;
    }

    if (error.message === 'SERVER_UNREACHABLE') {
      errorMessage = '서버와 연결할 수 없습니다. 다시 시도해주세요.';
      errorType = 'warning';
      showRetry = true;
    }

    setError({
      title: '채팅방 목록 로드 실패',
      message: errorMessage,
      type: errorType,
      showRetry,
    });

    setConnectionStatus(CONNECTION_STATUS.ERROR);
  }, [isRetrying, setConnectionStatus]);

  const requestRoomsPage = useCallback(async (page) => {
    const response = await axiosInstance.get('/api/rooms', { params: { page } });

    if (!response?.data?.data || !response?.data?.metadata) {
      throw new Error('INVALID_RESPONSE');
    }

    return response.data;
  }, []);

  const loadRooms = useCallback(async (requestedPage = currentPage) => {
    let payload = await requestRoomsPage(requestedPage);
    let resolvedPage = requestedPage;

    if (requestedPage > 0 && payload.data.length === 0) {
      if (payload.metadata.totalPages > 0 && requestedPage >= payload.metadata.totalPages) {
        resolvedPage = payload.metadata.totalPages - 1;
        payload = await requestRoomsPage(resolvedPage);
      } else if (payload.metadata.totalPages === 0) {
        resolvedPage = 0;
        payload = {
          ...payload,
          metadata: { ...payload.metadata, page: 0 },
        };
      }
    }

    setRooms(payload.data);
    setMetadata(payload.metadata);
    setCurrentPage(resolvedPage);
  }, [currentPage, requestRoomsPage]);

  const fetchRooms = useCallback(async () => {
    if (!currentUser?.token || isLoadingRef.current) {
      return;
    }

    try {
      isLoadingRef.current = true;

      setLoading(true);
      setError(null);

      await loadRooms();

      if (isInitialLoad) {
        setIsInitialLoad(false);
      }
    } catch (error) {
      handleFetchError(error);
    } finally {
      setLoading(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, isInitialLoad, loadRooms, handleFetchError]);

  /**
   * 이미 그려진 목록을 유지한 채 다시 조회한다.
   * 자동 갱신(silent)은 실패해도 화면을 흔들지 않고 다음 주기를 기다린다.
   */
  const refreshRooms = useCallback(async ({ silent = false } = {}) => {
    if (!currentUser?.token || isLoadingRef.current) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setRefreshing(true);

      await loadRooms(currentPage);
      setError(null);

      return true;
    } catch (error) {
      if (!silent) {
        setError({
          title: '채팅방 목록 갱신 실패',
          message: '목록을 갱신하지 못했습니다. 잠시 후 다시 시도해주세요.',
          type: 'warning',
          showRetry: false,
        });
      }

      return false;
    } finally {
      setRefreshing(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, currentPage, loadRooms]);

  const changePage = useCallback(async (page) => {
    if (
      !currentUser?.token ||
      isLoadingRef.current ||
      page < 0 ||
      page >= metadata.totalPages ||
      page === currentPage
    ) {
      return false;
    }

    try {
      isLoadingRef.current = true;
      setPageLoading(true);
      await loadRooms(page);
      setError(null);
      return true;
    } catch (error) {
      setError({
        title: '채팅방 페이지 이동 실패',
        message: '요청한 페이지를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.',
        type: 'warning',
        showRetry: false,
      });
      return false;
    } finally {
      setPageLoading(false);
      isLoadingRef.current = false;
    }
  }, [currentUser, currentPage, loadRooms, metadata.totalPages]);

  const handleJoinRoom = useCallback(async (roomId) => {
    if (connectionStatus !== CONNECTION_STATUS.CONNECTED) {
      setError({
        title: '채팅방 입장 실패',
        message: '서버와 연결이 끊어져 있습니다.',
        type: 'danger',
      });
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await axiosInstance.post(`/api/rooms/${roomId}/join`, {});

      if (response.data.success) {
        router.push(`/chat/${roomId}`);
      }
    } catch (error) {
      let errorMessage = '입장에 실패했습니다.';
      if (error.response?.status === 404) {
        errorMessage = '채팅방을 찾을 수 없습니다.';
      } else if (error.response?.status === 403) {
        errorMessage = '채팅방 입장 권한이 없습니다.';
      }

      setError({
        title: '채팅방 입장 실패',
        message: error.response?.data?.message || errorMessage,
        type: 'danger',
      });
    } finally {
      setJoiningRoom(false);
    }
  }, [connectionStatus, router]);

  return {
    rooms,
    setRooms,
    metadata,
    setMetadata,
    currentPage,
    error,
    setError,
    loading,
    refreshing,
    joiningRoom,
    pageLoading,
    fetchRooms,
    refreshRooms,
    changePage,
    handleJoinRoom,
  };
};

export default useRoomList;
