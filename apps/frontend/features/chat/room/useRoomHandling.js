import { useRef, useEffect, useCallback } from 'react';
import socketClient from '@/lib/socket/socketClient';
import { useAuth } from '@/contexts/AuthContext';
import { Toast } from '@/components/Toast';
import api, { getAuthHeaders } from '@/lib/api/client';
import {
  createRoomEventHandlers,
  processLoadedRoomMessages,
} from './roomEventHandlers';

export const useRoomHandling = ({
  roomId,
  route,
  state,
  refs,
  actions,
  cleanup,
  handleReactionUpdate,
}) => {
  const { onReplace, asPath } = route;
  const { currentUser } = state;
  const {
    socketRef,
    attachSocket,
    mountedRef,
    initializingRef,
    setupCompleteRef,
    userRooms,
    processedMessageIds,
    messageProcessingRef,
    initialLoadCompletedRef,
  } = refs;
  const {
    setRoom,
    setError,
    setMessages,
    setHasMoreMessages,
    setLoadingMessages,
    setupStarted,
    setupSucceeded,
    setupFailed,
  } = actions;
  const { user, refreshToken, logout } = useAuth();
  const setupPromiseRef = useRef(null);
  const roomEventsUnsubscribeRef = useRef(null);
  const MAX_SOCKET_RECONNECT_ATTEMPTS = 3;
  const MAX_MESSAGE_RETRY_ATTEMPTS = 3;
  const MESSAGE_TIMEOUT = 5000;
  const MESSAGE_RETRY_DELAY = 2000;

  const processMessages = useCallback(
    (loadedMessages, hasMore, isInitialLoad = false) => {
      processLoadedRoomMessages({
        loadedMessages,
        hasMore,
        isInitialLoad,
        processedMessageIds,
        setMessages,
        setHasMoreMessages,
        initialLoadCompletedRef,
      });
    },
    [
      processedMessageIds,
      setMessages,
      setHasMoreMessages,
      initialLoadCompletedRef,
    ]
  );

  const setupEventListeners = useCallback(() => {
    if (!socketRef.current || !mountedRef.current) return;

    if (roomEventsUnsubscribeRef.current) {
      roomEventsUnsubscribeRef.current();
      roomEventsUnsubscribeRef.current = null;
    }

    roomEventsUnsubscribeRef.current = socketClient.subscribeRoomEvents(
      socketRef.current,
      createRoomEventHandlers({
        mountedRef,
        messageProcessingRef,
        processedMessageIds,
        initialLoadCompletedRef,
        processMessages,
        setRoom,
        setMessages,
        setLoadingMessages,
        setError,
        setHasMoreMessages,
        cleanup,
        logout,
        onReplace,
        handleReactionUpdate,
        showRejectedMessage: Toast.error.bind(Toast),
      })
    );
  }, [
    processMessages,
    setHasMoreMessages,
    cleanup,
    handleReactionUpdate,
    setLoadingMessages,
    setError,
    logout,
    socketRef,
    mountedRef,
    messageProcessingRef,
    processedMessageIds,
    initialLoadCompletedRef,
    setRoom,
    setMessages,
    onReplace,
  ]);

  const handleSessionError = useCallback(async () => {
    try {
      if (!user) {
        throw new Error('No user session found');
      }

      await refreshToken();
      if (mountedRef.current) {
        return true;
      }
    } catch (error) {}

    if (mountedRef.current) {
      await logout();
      onReplace('/?redirect=' + asPath);
    }
    return false;
  }, [user, refreshToken, mountedRef, logout, onReplace, asPath]);

  const setupSocket = useCallback(async () => {
    try {
      if (!user?.token || !user?.sessionId) {
        throw new Error('Invalid authentication state');
      }

      if (socketRef.current?.connected) {
        return socketRef.current;
      }

      if (socketRef.current) {
        attachSocket(null);
      }

      const socket = await socketClient.connect({
        auth: {
          token: user.token,
          sessionId: user.sessionId,
        },
        transports: ['websocket', 'polling'],
        reconnection: true,
        reconnectionAttempts: MAX_SOCKET_RECONNECT_ATTEMPTS,
        reconnectionDelay: 1000,
        reconnectionDelayMax: 3000,
        timeout: 10000,
        pingTimeout: 10000,
        pingInterval: 8000,
        autoConnect: true,
      });

      return socket;
    } catch (error) {
      if (error.message === 'Invalid authentication state') {
        onReplace('/?error=auth_required');
      }
      throw error;
    }
  }, [onReplace, socketRef, attachSocket, user]);

  const fetchRoomData = useCallback(
    async (roomId) => {
      try {
        if (!user?.token || !user?.sessionId) {
          await handleSessionError();
          throw new Error('인증 정보가 유효하지 않습니다.');
        }

        if (!roomId || !mountedRef.current) {
          throw new Error('채팅방 정보가 올바르지 않습니다.');
        }

        let response;
        try {
          response = await api.get(`/api/rooms/${roomId}`, {
            handleAuthError: false,
            headers: getAuthHeaders(user),
            // Input readiness does not depend on the full participant list.
            // Avoid serializing every user in a hot public room before join.
            params: { includeRecentCount: false, includeParticipants: false },
          });
        } catch (error) {
          if (error.response?.status === 401) {
            const refreshed = await handleSessionError();
            if (refreshed && mountedRef.current) {
              return fetchRoomData(roomId);
            }
            throw new Error('인증이 만료되었습니다.');
          }
          throw error;
        }

        const data = response.data;
        if (!data.success || !data.data) {
          throw new Error('채팅방 데이터가 올바르지 않습니다.');
        }

        return data.data;
      } catch (error) {
        throw error;
      }
    },
    [user, mountedRef, handleSessionError]
  );

  const joinRoom = useCallback(
    async (roomId) => {
      if (!roomId || !mountedRef.current) {
        throw new Error('잘못된 채팅방 정보입니다.');
      }

      const socket = socketRef.current;
      if (!socket?.connected) {
        throw new Error('Socket not connected');
      }

      const data = await socketClient.joinRoomAndWait(roomId, socket);
      userRooms.current?.set(socket.id, roomId);
      return data;
    },
    [socketRef, mountedRef, userRooms]
  );

  const loadInitialMessages = useCallback(
    async (roomId) => {
      const loadMessagesWithRetry = async (retryCount = 0) => {
        const socket = socketRef.current;
        if (!socket?.connected) {
          throw new Error('Socket not connected');
        }

        try {
          const response = await socketClient.fetchPreviousMessagesAndWait(
            { roomId, limit: 30 },
            socket,
            { timeoutMs: MESSAGE_TIMEOUT }
          );

          if (!response || !Array.isArray(response.messages)) {
            throw new Error('잘못된 메시지 응답 형식입니다.');
          }

          processMessages(response.messages, response.hasMore, true);
          return response;
        } catch (error) {
          if (retryCount < MAX_MESSAGE_RETRY_ATTEMPTS) {
            await new Promise((resolve) =>
              setTimeout(resolve, MESSAGE_RETRY_DELAY)
            );
            return loadMessagesWithRetry(retryCount + 1);
          }

          throw error;
        }
      };

      try {
        return await loadMessagesWithRetry();
      } catch (error) {
        if (!socketRef.current?.connected) {
          // setupSocket 은 낡은 소켓을 버리고 새 소켓을 반환한다. 받아서 걸어주지
          // 않으면 ref 가 비어 있어 재시도가 곧바로 'Socket not connected' 로 죽는다.
          attachSocket(await setupSocket());
          return loadMessagesWithRetry();
        }
        throw error;
      }
    },
    [socketRef, attachSocket, processMessages, setupSocket]
  );

  const loadInitialMessagesInBackground = useCallback(
    (targetRoomId) => {
      loadInitialMessages(targetRoomId).catch((error) => {
        if (mountedRef.current) {
          setError(error.message || '메시지를 불러오지 못했습니다.');
        }
      });
    },
    [loadInitialMessages, mountedRef, setError]
  );

  // 재연결 뒤에는 Socket room 참가 상태를 먼저 복구하고, 놓친 메시지는
  // 입장 ACK를 막지 않도록 별도로 조회한다.
  const rejoinRoom = useCallback(async () => {
    const socket = socketRef.current;
    if (!roomId || !mountedRef.current || !socket?.connected) {
      return;
    }

    await joinRoom(roomId);

    if (mountedRef.current) {
      setupCompleteRef.current = true;
      loadInitialMessagesInBackground(roomId);
    }
  }, [
    roomId,
    socketRef,
    mountedRef,
    setupCompleteRef,
    joinRoom,
    loadInitialMessagesInBackground,
  ]);

  const setupRoom = useCallback(async () => {
    if (setupPromiseRef.current) {
      return setupPromiseRef.current;
    }

    setupPromiseRef.current = (async () => {
      try {
        initializingRef.current = true;
        setupStarted();
        // Socket 연결과 방 상세 조회는 서로 의존하지 않는다.
        // 순차 실행하면 두 지연이 합산되어 입력창 렌더링이 늦어지므로
        // 병렬로 시작한다. 두 작업이 모두 정리된 후 결과를 처리해,
        // 한쪽이 실패해도 이미 연결된 소켓을 catch 블록에서 정리할 수 있게 한다.
        const [socketResult, roomResult] = await Promise.allSettled([
          setupSocket(),
          fetchRoomData(roomId),
        ]);

        if (socketResult.status === 'rejected') {
          throw socketResult.reason;
        }
        attachSocket(socketResult.value);

        if (roomResult.status === 'rejected') {
          throw roomResult.reason;
        }
        const roomData = roomResult.value;

        // Ensure current user is included in participants for display
        if (currentUser && roomData.participants) {
          const isUserInParticipants = roomData.participants.some(
            (p) => p._id === currentUser.id || p.id === currentUser.id
          );

          if (!isUserInParticipants) {
            roomData.participants = [
              ...roomData.participants,
              {
                _id: currentUser.id,
                id: currentUser.id,
                name: currentUser.name,
                email: currentUser.email,
              },
            ];
          }
        }

        // 3. Setup Event Listeners
        if (mountedRef.current) {
          setupEventListeners();
        }

        // 4. Join Room and Load Messages
        if (mountedRef.current && socketRef.current?.connected) {
          await joinRoom(roomId);
        }

        if (mountedRef.current) {
          setupCompleteRef.current = true;
          setupSucceeded(roomData);
          loadInitialMessagesInBackground(roomId);
        }
      } catch (error) {
        if (mountedRef.current) {
          const errorMessage = error.message.includes('시간 초과')
            ? '채팅방 연결 시간이 초과되었습니다.'
            : error.message || '채팅방 연결에 실패했습니다.';

          setupFailed(errorMessage);
          cleanup('ERROR');

          if (socketRef.current) {
            socketClient.tryLeaveRoom(roomId, socketRef.current);
            attachSocket(null);
          }
        }

        throw error;
      } finally {
        if (mountedRef.current) {
          initializingRef.current = false;
        }

        setupPromiseRef.current = null;
      }
    })();

    return setupPromiseRef.current;
  }, [
    roomId,
    socketRef,
    attachSocket,
    mountedRef,
    setupSocket,
    fetchRoomData,
    joinRoom,
    loadInitialMessagesInBackground,
    cleanup,
    setupEventListeners,
    setupStarted,
    setupSucceeded,
    setupFailed,
    currentUser,
    initializingRef,
    setupCompleteRef,
  ]);

  useEffect(() => {
    return () => {
      setupPromiseRef.current = null;
      initializingRef.current = false;
      setupCompleteRef.current = false;

      if (roomEventsUnsubscribeRef.current) {
        roomEventsUnsubscribeRef.current();
        roomEventsUnsubscribeRef.current = null;
      }

      // Keep the shared Socket.IO connection alive for room-list or the next room.
      if (socketRef.current) {
        socketClient.tryLeaveRoom(roomId, socketRef.current);
        socketRef.current = null;
      }
    };
  }, [roomId, socketRef]);

  return {
    setupRoom,
    rejoinRoom,
    loadInitialMessages,
  };
};

export default useRoomHandling;
