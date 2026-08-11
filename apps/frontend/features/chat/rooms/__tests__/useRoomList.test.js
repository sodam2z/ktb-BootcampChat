import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import axiosInstance from '@/services/axios';
import { useRoomList } from '../useRoomList';
import { CONNECTION_STATUS } from '../useServerConnection';

vi.mock('@/services/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const roomsResponse = (rooms, metadata = {}) => ({
  data: {
    data: rooms,
    metadata: {
      total: rooms.length,
      page: 0,
      pageSize: 20,
      totalPages: rooms.length > 0 ? 1 : 0,
      hasMore: false,
      currentCount: rooms.length,
      sort: { field: 'createdAt', order: 'DESC' },
      ...metadata,
    },
  },
});

const renderRoomList = () =>
  renderHook(() =>
    useRoomList({
      currentUser: { token: 'token-1' },
      router: { push: vi.fn() },
      connectionStatus: CONNECTION_STATUS.CONNECTED,
      setConnectionStatus: vi.fn(),
      retryCount: 0,
      setRetryCount: vi.fn(),
      isRetrying: false,
      setIsRetrying: vi.fn(),
      getRetryDelay: vi.fn(() => 1000),
      attemptConnection: vi.fn(() => Promise.resolve(true)),
    })
  );

describe('useRoomList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('replaces the list on refresh without leaving the refreshing flag on', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.refreshing).toBe(false);
    expect(axiosInstance.get).toHaveBeenCalledWith('/api/rooms', { params: { page: 0 } });
  });

  it('requests rooms without waiting for the socket connection', async () => {
    axiosInstance.get.mockResolvedValue(roomsResponse([]));
    const neverConnects = new Promise(() => {});

    const { result } = renderHook(() =>
      useRoomList({
        currentUser: { token: 'token-1' },
        router: { push: vi.fn() },
        connectionStatus: CONNECTION_STATUS.CHECKING,
        setConnectionStatus: vi.fn(),
        isRetrying: false,
        attemptConnection: vi.fn(() => neverConnects),
      })
    );

    await act(async () => {
      await result.current.fetchRooms();
    });

    expect(axiosInstance.get).toHaveBeenCalledWith('/api/rooms', { params: { page: 0 } });
  });

  it('keeps the current list and stays quiet when a silent refresh fails', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    await act(async () => {
      await result.current.refreshRooms({ silent: true });
    });

    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(false);
  });

  it('surfaces a refresh failure when the user asked for it', async () => {
    axiosInstance.get.mockRejectedValue(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toMatchObject({
      title: '채팅방 목록 갱신 실패',
      showRetry: false,
    });
  });

  it('clears a previous error once a refresh succeeds', async () => {
    axiosInstance.get.mockRejectedValueOnce(new Error('SERVER_UNREACHABLE'));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).not.toBeNull();

    axiosInstance.get.mockResolvedValueOnce(roomsResponse([{ _id: 'room-1' }]));

    await act(async () => {
      await result.current.refreshRooms();
    });

    expect(result.current.error).toBeNull();
    expect(result.current.rooms).toEqual([{ _id: 'room-1' }]);
  });

  it('loads a selected page and replaces the current rows', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse(
      [{ _id: 'room-1' }],
      { total: 21, totalPages: 2, hasMore: true },
    ));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockResolvedValueOnce(roomsResponse(
      [{ _id: 'room-21' }],
      { total: 21, page: 1, totalPages: 2 },
    ));

    await act(async () => {
      await result.current.changePage(1);
    });

    expect(axiosInstance.get).toHaveBeenLastCalledWith('/api/rooms', { params: { page: 1 } });
    expect(result.current.currentPage).toBe(1);
    expect(result.current.rooms).toEqual([{ _id: 'room-21' }]);
    expect(result.current.pageLoading).toBe(false);
  });

  it('moves to the last valid page when the current page becomes empty', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse(
      [{ _id: 'room-1' }],
      { total: 41, totalPages: 3, hasMore: true },
    ));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get
      .mockResolvedValueOnce(roomsResponse([], { total: 40, page: 2, totalPages: 2 }))
      .mockResolvedValueOnce(roomsResponse(
        [{ _id: 'room-40' }],
        { total: 40, page: 1, totalPages: 2 },
      ));

    await act(async () => {
      await result.current.changePage(2);
    });

    expect(axiosInstance.get).toHaveBeenNthCalledWith(3, '/api/rooms', { params: { page: 1 } });
    expect(result.current.currentPage).toBe(1);
    expect(result.current.rooms).toEqual([{ _id: 'room-40' }]);
  });

  it('returns to page zero when every room has been removed', async () => {
    axiosInstance.get.mockResolvedValueOnce(roomsResponse(
      [{ _id: 'room-1' }],
      { total: 21, totalPages: 2, hasMore: true },
    ));

    const { result } = renderRoomList();

    await act(async () => {
      await result.current.fetchRooms();
    });

    axiosInstance.get.mockResolvedValueOnce(roomsResponse(
      [],
      { total: 0, page: 1, totalPages: 0 },
    ));

    await act(async () => {
      await result.current.changePage(1);
    });

    expect(result.current.currentPage).toBe(0);
    expect(result.current.metadata.page).toBe(0);
    expect(result.current.rooms).toEqual([]);
  });
});
