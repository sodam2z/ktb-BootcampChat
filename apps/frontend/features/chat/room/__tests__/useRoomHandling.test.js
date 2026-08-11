import { act, renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from '@/lib/api/client';
import socketClient from '@/lib/socket/socketClient';
import { Toast } from '@/components/Toast';
import { useRoomHandling } from '../useRoomHandling';

const authMocks = vi.hoisted(() => ({
  user: {
    id: 'user-1',
    token: 'token-1',
    sessionId: 'session-1',
    name: 'Tester',
    email: 'tester@example.com',
  },
  refreshToken: vi.fn(),
  logout: vi.fn(),
}));

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: authMocks.user,
    refreshToken: authMocks.refreshToken,
    logout: authMocks.logout,
  }),
}));

vi.mock('@/lib/api/client', () => ({
  default: {
    get: vi.fn(),
  },
  getAuthHeaders: vi.fn(() => ({ Authorization: 'Bearer token-1' })),
}));

vi.mock('@/lib/socket/socketClient', () => ({
  default: {
    connect: vi.fn(),
    leaveRoom: vi.fn(),
    joinRoomAndWait: vi.fn(),
    fetchPreviousMessagesAndWait: vi.fn(),
    subscribeRoomEvents: vi.fn(),
  },
}));

vi.mock('@/components/Toast', () => ({
  Toast: { error: vi.fn() },
}));

const createSocket = () => ({
  id: 'socket-1',
  connected: true,
  disconnect: vi.fn(),
  removeAllListeners: vi.fn(),
  removeListener: vi.fn(),
  on: vi.fn(),
  once: vi.fn(),
  off: vi.fn(),
});

const createHarness = () => {
  const socketRef = { current: null };
  const mountedRef = { current: true };
  const initializingRef = { current: false };
  const setupCompleteRef = { current: false };
  const processedMessageIds = { current: new Set() };
  const messageProcessingRef = { current: false };
  const initialLoadCompletedRef = { current: false };
  const userRooms = new Map();

  const setters = {
    setRoom: vi.fn(),
    setError: vi.fn(),
    setMessages: vi.fn(),
    setHasMoreMessages: vi.fn(),
    setLoadingMessages: vi.fn(),
    setLoading: vi.fn(),
    cleanup: vi.fn(),
    setIsInitialized: vi.fn(),
  };
  const reducerActions = {
    setupStarted: vi.fn(),
    setupSucceeded: vi.fn(),
    setupFailed: vi.fn(),
    cleanupManual: vi.fn(),
  };

  const state = {
    currentUser: {
      id: 'user-1',
      name: 'Tester',
      email: 'tester@example.com',
    },
    messages: [],
  };
  const refs = {
    socketRef,
    attachSocket: (socket) => {
      socketRef.current = socket;
    },
    mountedRef,
    initializingRef,
    setupCompleteRef,
    userRooms: { current: userRooms },
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
  };
  const actions = {
    ...setters,
    ...reducerActions,
  };

  const hook = renderHook(() =>
    useRoomHandling({
      roomId: 'room-1',
      route: {
        onNavigate: vi.fn(),
        onReplace: vi.fn(),
        asPath: '/chat/room-1',
      },
      state,
      refs,
      actions,
      cleanup: setters.cleanup,
      handleReactionUpdate: vi.fn(),
    })
  );

  return {
    ...hook,
    socketRef,
    mountedRef,
    initializingRef,
    setupCompleteRef,
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
    userRooms,
    setters,
    actions,
  };
};

const createStableSetupHarness = () => {
  const baseHarness = createHarness();
  const route = {
    onNavigate: vi.fn(),
    onReplace: vi.fn(),
    asPath: '/chat/room-1',
  };
  const handleReactionUpdate = vi.fn();
  const refs = {
    socketRef: baseHarness.socketRef,
    attachSocket: (socket) => {
      baseHarness.socketRef.current = socket;
    },
    mountedRef: baseHarness.mountedRef,
    initializingRef: baseHarness.initializingRef,
    setupCompleteRef: baseHarness.setupCompleteRef,
    userRooms: { current: baseHarness.userRooms },
    processedMessageIds: baseHarness.processedMessageIds,
    messageProcessingRef: baseHarness.messageProcessingRef,
    initialLoadCompletedRef: baseHarness.initialLoadCompletedRef,
  };
  const state = {
    currentUser: {
      id: 'user-1',
      name: 'Tester',
      email: 'tester@example.com',
    },
    messages: [],
  };

  baseHarness.unmount();

  const hook = renderHook(({ messages }) =>
    useRoomHandling({
      roomId: 'room-1',
      route,
      state: {
        ...state,
        messages,
      },
      refs,
      actions: baseHarness.actions,
      cleanup: baseHarness.setters.cleanup,
      handleReactionUpdate,
    }),
    {
      initialProps: { messages: [] },
    }
  );

  return hook;
};

describe('useRoomHandling', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.get.mockResolvedValue({
      data: {
        success: true,
        data: {
          _id: 'room-1',
          name: 'Room 1',
          participants: [],
        },
      },
    });
    socketClient.connect.mockResolvedValue(createSocket());
    socketClient.joinRoomAndWait.mockResolvedValue({
      roomId: 'room-1',
    });
    socketClient.fetchPreviousMessagesAndWait.mockResolvedValue({
      messages: [{ _id: 'message-1', timestamp: '2026-07-07T00:00:00.000Z' }],
      hasMore: false,
    });
    socketClient.subscribeRoomEvents.mockReturnValue(vi.fn());
  });

  it('exposes only the room lifecycle boundary consumed by useChatRoomLifecycle', () => {
    const harness = createHarness();

    expect(Object.keys(harness.result.current).sort()).toEqual([
      'loadInitialMessages',
      'rejoinRoom',
      'setupRoom',
    ]);
  });

  it('keeps setupRoom stable when message state changes', () => {
    const { result, rerender } = createStableSetupHarness();
    const initialSetupRoom = result.current.setupRoom;

    rerender({ messages: [{ _id: 'message-1' }] });

    expect(result.current.setupRoom).toBe(initialSetupRoom);
  });

  it('sets up a room through socketClient lifecycle APIs', async () => {
    const harness = createHarness();

    await act(async () => {
      await harness.result.current.setupRoom();
    });

    expect(socketClient.connect).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith('/api/rooms/room-1', expect.any(Object));
    expect(socketClient.subscribeRoomEvents).toHaveBeenCalledWith(
      harness.socketRef.current,
      expect.objectContaining({
        onParticipantsUpdate: expect.any(Function),
        onMessagesRead: expect.any(Function),
        onMessage: expect.any(Function),
        onPreviousMessagesLoaded: expect.any(Function),
        onMessageReactionUpdate: expect.any(Function),
        onSessionEnded: expect.any(Function),
        onError: expect.any(Function),
      }),
    );
    expect(socketClient.joinRoomAndWait).toHaveBeenCalledWith('room-1', harness.socketRef.current);
    expect(socketClient.fetchPreviousMessagesAndWait).toHaveBeenCalledWith(
      { roomId: 'room-1', limit: 30 },
      harness.socketRef.current,
      expect.objectContaining({ timeoutMs: 5000 }),
    );
    expect(harness.setters.setMessages).toHaveBeenCalledWith(expect.any(Function));
    expect(harness.setters.setHasMoreMessages).toHaveBeenCalledWith(false);
    expect(harness.initialLoadCompletedRef.current).toBe(true);
    expect(harness.processedMessageIds.current.has('message-1')).toBe(true);
    expect(harness.actions.setupStarted).toHaveBeenCalledTimes(1);
    expect(harness.actions.setupSucceeded).toHaveBeenCalledWith(
      expect.objectContaining({ _id: 'room-1' }),
    );
    expect(harness.setters.setIsInitialized).not.toHaveBeenCalled();
    expect(harness.setupCompleteRef.current).toBe(true);
  });

  it('marks room setup complete without waiting for the initial message response', async () => {
    let resolveMessageLoad;
    socketClient.fetchPreviousMessagesAndWait.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveMessageLoad = resolve;
      }),
    );
    const harness = createHarness();

    await act(async () => {
      await harness.result.current.setupRoom();
    });

    expect(harness.actions.setupSucceeded).toHaveBeenCalledWith(
      expect.objectContaining({ _id: 'room-1' }),
    );
    expect(harness.setupCompleteRef.current).toBe(true);
    expect(harness.initialLoadCompletedRef.current).toBe(false);
    expect(socketClient.fetchPreviousMessagesAndWait).toHaveBeenCalledWith(
      { roomId: 'room-1', limit: 30 },
      harness.socketRef.current,
      expect.objectContaining({ timeoutMs: 5000 }),
    );

    await act(async () => {
      resolveMessageLoad({
        messages: [{ _id: 'message-late', timestamp: '2026-07-07T00:00:00.000Z' }],
        hasMore: false,
      });
    });

    expect(harness.initialLoadCompletedRef.current).toBe(true);
  });

  it('restores Socket room membership without waiting for missed messages', async () => {
    const harness = createHarness();
    await act(async () => {
      await harness.result.current.setupRoom();
    });

    vi.clearAllMocks();
    let resolveMessageLoad;
    socketClient.fetchPreviousMessagesAndWait.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveMessageLoad = resolve;
      }),
    );

    await act(async () => {
      await harness.result.current.rejoinRoom();
    });

    expect(socketClient.joinRoomAndWait).toHaveBeenCalledWith(
      'room-1',
      harness.socketRef.current,
    );
    expect(socketClient.fetchPreviousMessagesAndWait).toHaveBeenCalled();
    expect(harness.setupCompleteRef.current).toBe(true);

    await act(async () => {
      resolveMessageLoad({ messages: [], hasMore: false });
    });
  });

  it('records setup failure through semantic reducer actions', async () => {
    api.get.mockResolvedValueOnce({
      data: {
        success: false,
      },
    });
    const harness = createHarness();

    await expect(
      act(async () => {
        await harness.result.current.setupRoom();
      })
    ).rejects.toThrow('채팅방 데이터가 올바르지 않습니다.');

    expect(harness.actions.setupStarted).toHaveBeenCalledTimes(1);
    expect(harness.actions.setupFailed).toHaveBeenCalledWith(
      '채팅방 데이터가 올바르지 않습니다.',
    );
    expect(harness.setters.cleanup).toHaveBeenCalledWith('ERROR');
    expect(harness.setters.setError).not.toHaveBeenCalledWith(
      '채팅방 데이터가 올바르지 않습니다.',
    );
  });

  it('updates room state from subscribed room event handlers', async () => {
    const harness = createHarness();

    await act(async () => {
      await harness.result.current.setupRoom();
    });

    const handlers = socketClient.subscribeRoomEvents.mock.calls[0][1];
    act(() => {
      handlers.onParticipantsUpdate([{ _id: 'user-2' }]);
      handlers.onMessagesRead({
        userId: 'user-2',
        messageIds: ['message-1'],
        timestamp: '2026-07-07T00:00:01.000Z',
      });
      handlers.onMessage({ _id: 'message-2', timestamp: '2026-07-07T00:00:02.000Z' });
      handlers.onPreviousMessagesLoaded({
        messages: [{ _id: 'message-3', timestamp: '2026-07-07T00:00:03.000Z' }],
        hasMore: true,
      });
      handlers.onError({
        code: 'MESSAGE_REJECTED',
        message: '금칙어가 포함되어 메시지를 전송할 수 없습니다.',
      });
    });

    expect(harness.setters.setRoom).toHaveBeenCalledWith(expect.any(Function));
    expect(harness.setters.setMessages).toHaveBeenCalled();
    expect(harness.setters.setHasMoreMessages).toHaveBeenCalledWith(true);
    expect(Toast.error).toHaveBeenCalledWith('금칙어가 포함되어 메시지를 전송할 수 없습니다.');
  });
});
