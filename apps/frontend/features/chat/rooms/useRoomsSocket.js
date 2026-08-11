import { useRef, useEffect } from 'react';
import socketClient from '@/lib/socket/socketClient';

const CONNECTION_STATUS = {
  CONNECTED: 'connected',
  DISCONNECTED: 'disconnected',
  ERROR: 'error',
};

export const useRoomsSocket = ({
  currentUser,
  setConnectionStatus,
  setRooms,
  currentPage,
  setMetadata,
}) => {
  const socketRef = useRef(null);
  const currentPageRef = useRef(currentPage);

  useEffect(() => {
    currentPageRef.current = currentPage;
  }, [currentPage]);

  useEffect(() => {
    if (!currentUser?.token) return;

    let isSubscribed = true;
    let subscribedSocket = null;
    let subscribedHandlers = null;

    const unsubscribeSocketEvents = () => {
      if (!subscribedSocket || !subscribedHandlers) {
        return;
      }

      Object.entries(subscribedHandlers).forEach(([event, handler]) => {
        subscribedSocket.off?.(event, handler);
      });
      subscribedSocket = null;
      subscribedHandlers = null;
    };

    const connectSocket = async () => {
      try {
        const socket = await socketClient
          .connect({
            auth: {
              token: currentUser.token,
              sessionId: currentUser.sessionId,
            },
          })
          .catch((err) => {
            console.log('Socket connection error:', err);
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          });

        if (!isSubscribed || !socket) return;

        socketRef.current = socket;
        setConnectionStatus(CONNECTION_STATUS.CONNECTED);

        const handlers = {
          connect: () => {
            setConnectionStatus(CONNECTION_STATUS.CONNECTED);
          },
          disconnect: () => {
            setConnectionStatus(CONNECTION_STATUS.DISCONNECTED);
          },
          error: () => {
            setConnectionStatus(CONNECTION_STATUS.ERROR);
          },
          roomCreated: (newRoom) => {
            setMetadata((prev) => {
              const total = prev.total + 1;
              return {
                ...prev,
                total,
                totalPages: Math.ceil(total / prev.pageSize),
              };
            });

            if (currentPageRef.current === 0) {
              setRooms((prev) => [
                newRoom,
                ...prev.filter((room) => room._id !== newRoom._id),
              ].slice(0, 20));
            }
          },
          roomUpdated: (updatedRoom) => {
            setRooms((prev) =>
              prev.map((room) =>
                room._id === updatedRoom._id ? updatedRoom : room
              )
            );
          },
          // 활성도 지표만 담긴 경량 payload이므로 방 정보를 덮지 않고 병합한다
          roomActivity: (activity) => {
            if (!activity?._id) return;

            setRooms((prev) =>
              prev.map((room) =>
                room._id === activity._id
                  ? { ...room, recentMessageCount: activity.recentMessageCount }
                  : room
              )
            );
          },
        };

        unsubscribeSocketEvents();
        subscribedSocket = socket;
        subscribedHandlers = handlers;
        Object.entries(handlers).forEach(([event, handler]) => {
          socket.on(event, handler);
        });
      } catch (error) {
        if (!isSubscribed) return;

        if (
          error.message?.includes('Authentication required') ||
          error.message?.includes('Invalid session')
        ) {
          // Auth error will be handled by the useAuth context
        }

        setConnectionStatus(CONNECTION_STATUS.ERROR);
      }
    };

    connectSocket();

    return () => {
      isSubscribed = false;
      unsubscribeSocketEvents();
      socketRef.current = null;
    };
  }, [currentUser]); // eslint-disable-line react-hooks/exhaustive-deps

  return { socketRef };
};

export default useRoomsSocket;
