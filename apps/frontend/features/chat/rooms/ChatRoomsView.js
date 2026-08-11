import React, { useEffect, useRef } from 'react';
import { ErrorCircleIcon, NetworkIcon, RefreshOutlineIcon } from '@vapor-ui/icons';
import { Button, Text, Badge, Callout, Box, VStack, HStack, Spinner } from '@vapor-ui/core';
import { useAuth } from '@/contexts/AuthContext';
import { useRoomsSocket } from './useRoomsSocket';
import {
  useServerConnection,
  CONNECTION_STATUS,
} from './useServerConnection';
import { useRoomList } from './useRoomList';
import RoomsTable from './RoomsTable';
import ConnectionErrorBanner from '@/components/ConnectionErrorBanner';

const STATUS_CONFIG = {
  [CONNECTION_STATUS.CHECKING]: { label: "연결 확인 중...", color: "warning" },
  [CONNECTION_STATUS.CONNECTING]: { label: "연결 중...", color: "warning" },
  [CONNECTION_STATUS.CONNECTED]: { label: "연결됨", color: "success" },
  [CONNECTION_STATUS.DISCONNECTED]: { label: "연결 끊김", color: "danger" },
  [CONNECTION_STATUS.ERROR]: { label: "연결 오류", color: "danger" },
};

const ROOM_LIST_REFRESH_INTERVAL = 30000;

const LoadingIndicator = ({ text }) => (
  <HStack $css={{ gap: '$200', justifyContent: 'center', alignItems: 'center' }}>
    <Spinner size="md" colorPalette="primary" aria-label={text} />
    <Text typography="body2">{text}</Text>
  </HStack>
);

export default function ChatRoomsView({ router }) {
  const { user: currentUser } = useAuth();
  const currentUserKey = currentUser?.id || currentUser?._id || currentUser?.email || currentUser?.token;

  const {
    connectionStatus,
    setConnectionStatus,
    isRetrying,
  } = useServerConnection();

  const {
    rooms,
    setRooms,
    metadata,
    setMetadata,
    currentPage,
    error,
    loading,
    refreshing,
    joiningRo선om,
    pageLoading,
    fetchRooms,
    refreshRooms,
    changePage,
    handleJoinRoom,
  } = useRoomList({
    currentUser,
    router,
    connectionStatus,
    setConnectionStatus,
    isRetrying,
  });

  const initialFetchStartedRef = useRef(false);
  const refreshRoomsRef = useRef(refreshRooms);

  useEffect(() => {
    refreshRoomsRef.current = refreshRooms;
  }, [refreshRooms]);

  useEffect(() => {
    if (!currentUserKey) {
      initialFetchStartedRef.current = false;
      return;
    }

    if (initialFetchStartedRef.current) return;

    initialFetchStartedRef.current = true;

    let retryTimer = null;
    let cancelled = false;

    const initFetch = async () => {
      try {
        await fetchRooms();
      } catch (error) {
        retryTimer = setTimeout(() => {
          if (!cancelled) {
            fetchRooms();
          }
        }, 3000);
      }
    };

    initFetch();

    return () => {
      cancelled = true;
      if (retryTimer) {
        clearTimeout(retryTimer);
      }
    };
  }, [currentUserKey, fetchRooms]);

  // 활성도 지표는 소켓 이벤트만으로 만료를 알 수 없어 주기적으로 다시 조회한다.
  // 보이지 않는 탭에서는 갱신을 멈추고, 다시 보일 때 즉시 한 번 따라잡는다.
  useEffect(() => {
    if (!currentUserKey || connectionStatus !== CONNECTION_STATUS.CONNECTED) return;

    const refreshWhenVisible = () => {
      if (document.visibilityState !== 'visible') return;
      refreshRoomsRef.current({ silent: true });
    };

    const refreshTimer = setInterval(refreshWhenVisible, ROOM_LIST_REFRESH_INTERVAL);
    document.addEventListener('visibilitychange', refreshWhenVisible);

    return () => {
      clearInterval(refreshTimer);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, [currentUserKey, connectionStatus]);

  useRoomsSocket({
    currentUser,
    setConnectionStatus,
    setRooms,
    currentPage,
    setMetadata,
  });

  return (
    <Box
      $css={{
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '100vh',
        padding: '$300',
      }}
    >
      <VStack
        $css={{
          gap: '$400',
          width: '100%',
          maxWidth: '1200px',
          padding: '$400',
          borderRadius: '$300',
          border: '1px solid var(--vapor-color-border-normal)',
        }}
      >
        <VStack $css={{ gap: '$300', alignItems: 'center' }}>
          <HStack
            className="w-full"
            $css={{ gap: '$300', alignItems: 'center', justifyContent: 'space-between' }}
          >
            <Text typography="heading3">채팅방 목록</Text>
            <HStack $css={{ gap: '$200' }}>
              <Badge colorPalette={STATUS_CONFIG[connectionStatus]?.color || 'danger'}>
                {STATUS_CONFIG[connectionStatus].label}
              </Badge>
              {error || connectionStatus === CONNECTION_STATUS.ERROR ? (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => fetchRooms()}
                  disabled={isRetrying}
                >
                  <RefreshOutlineIcon size={16} />
                  재연결
                </Button>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => refreshRooms()}
                  disabled={refreshing || loading}
                  data-testid="refresh-rooms-button"
                >
                  <RefreshOutlineIcon size={16} />
                  {refreshing ? '갱신 중' : '새로고침'}
                </Button>
              )}
            </HStack>
          </HStack>
        </VStack>

        
        {error && (
          <Callout.Root
            colorPalette={error.type === 'danger' ? 'danger' : error.type === 'warning' ? 'warning' : 'primary'}
          >
            <HStack $css={{ gap: '$200', alignItems: 'flex-start' }}>
              <Callout.Icon>
                {connectionStatus === CONNECTION_STATUS.ERROR ? (
                  <NetworkIcon size={18} />
                ) : (
                  <ErrorCircleIcon size={18} />
                )}
              </Callout.Icon>
              <VStack $css={{ gap: '$150', alignItems: 'flex-start' }}>
                <Text typography="subtitle2" style={{ fontWeight: 500 }}>{error.title}</Text>
                <Text typography="body2">{error.message}</Text>
                {error.showRetry && !isRetrying && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => fetchRooms()}
                  >
                    다시 시도
                  </Button>
                )}
              </VStack>
            </HStack>
          </Callout.Root>
        )}

        {connectionStatus === CONNECTION_STATUS.ERROR ? (
          <ConnectionErrorBanner message="채팅 서버와 연결할 수 없습니다. 잠시 후 다시 시도해주세요." />
        ) : loading ? (
          <Box $css={{ padding: '$400' }}>
            <LoadingIndicator text="채팅방 목록을 불러오는 중..." />
          </Box>
        ) : rooms.length > 0 ? (
          <RoomsTable
            rooms={rooms}
            metadata={metadata}
            currentPage={currentPage}
            pageLoading={pageLoading}
            onPageChange={changePage}
            connectionStatus={connectionStatus}
            onJoinRoom={handleJoinRoom}
          />
        ) : !error && (
          <VStack
            $css={{ gap: '$300', alignItems: 'center', padding: '$400' }}
            data-testid="rooms-empty"
          >
            <Text typography="body1">생성된 채팅방이 없습니다.</Text>
            <Button
              colorPalette="primary"
              onClick={() => router.push('/chat/new')}
              disabled={connectionStatus !== CONNECTION_STATUS.CONNECTED}
            >
              새 채팅방 만들기
            </Button>
          </VStack>
        )}
      </VStack>
    </Box>
  );
}
