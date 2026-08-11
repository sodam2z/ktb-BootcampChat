import React from 'react';
import { LockIcon, GroupIcon } from '@vapor-ui/icons';
import { Button, Text, VStack, HStack } from '@vapor-ui/core';
import * as Table from '@/components/Table';
import { CONNECTION_STATUS } from './useServerConnection';

export const getVisiblePages = (currentPage, totalPages) => {
  const visibleCount = Math.min(5, totalPages);
  const start = Math.max(
    0,
    Math.min(currentPage - 2, totalPages - visibleCount)
  );
  return Array.from({ length: visibleCount }, (_, index) => start + index);
};

const RoomsTable = ({
  rooms,
  metadata,
  currentPage,
  pageLoading,
  onPageChange,
  connectionStatus,
  onJoinRoom,
}) => {
  if (!rooms || rooms.length === 0) return null;

  const totalPages = metadata?.totalPages || 0;
  const visiblePages = getVisiblePages(currentPage, totalPages);
  const visibleRooms = rooms.slice(0, metadata?.pageSize || 20);

  return (
    <VStack $css={{ gap: '$200', width: '100%' }}>
      <div
        className="chat-rooms-table"
        aria-busy={pageLoading}
        style={{
          height: '430px',
          overflowY: 'auto',
          position: 'relative',
          borderRadius: '0.5rem',
          backgroundColor: 'var(--background-normal)',
          border: '1px solid var(--border-color)',
          scrollBehavior: 'smooth',
          WebkitOverflowScrolling: 'touch',
          opacity: pageLoading ? 0.65 : 1,
        }}
      >
        <Table.Root style={{ width: '100%' }}>
          <Table.ColumnGroup>
            <Table.Column style={{ width: '40%' }} />
            <Table.Column style={{ width: '12%' }} />
            <Table.Column style={{ width: '12%' }} />
            <Table.Column style={{ width: '21%' }} />
            <Table.Column style={{ width: '15%' }} />
          </Table.ColumnGroup>

          <Table.Header>
            <Table.Row>
              <Table.Heading>채팅방</Table.Heading>
              <Table.Heading>참여자</Table.Heading>
              <Table.Heading>최근 메시지</Table.Heading>
              <Table.Heading>생성일</Table.Heading>
              <Table.Heading>액션</Table.Heading>
            </Table.Row>
          </Table.Header>

          <Table.Body>
            {visibleRooms.map((room) => (
              <Table.Row key={room._id}>
                <Table.Cell>
                  <VStack $css={{ gap: '$050', alignItems: 'flex-start' }}>
                    <Text style={{ fontWeight: 500 }}>{room.name}</Text>
                    {room.hasPassword && (
                      <HStack
                        $css={{
                          gap: '$050',
                          alignItems: 'center',
                          color: '$warning-100',
                        }}
                      >
                        <LockIcon size={16} />
                        <Text typography="body3" foreground="warning-100">
                          비밀번호 필요
                        </Text>
                      </HStack>
                    )}
                  </VStack>
                </Table.Cell>
                <Table.Cell>
                  <HStack $css={{ gap: '$050', alignItems: 'center' }}>
                    <GroupIcon />
                    <Text typography="body2">{room.participantCount || 0}</Text>
                  </HStack>
                </Table.Cell>
                <Table.Cell>
                  {room.recentMessageCount > 0 ? room.recentMessageCount : '-'}
                </Table.Cell>
                <Table.Cell>
                  <time dateTime={new Date(room.createdAt).toISOString()}>
                    {new Date(room.createdAt).toLocaleString('ko-KR', {
                      year: 'numeric',
                      month: '2-digit',
                      day: '2-digit',
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </time>
                </Table.Cell>
                <Table.Cell>
                  <Button
                    colorPalette="primary"
                    size="md"
                    onClick={() => onJoinRoom(room._id)}
                    disabled={connectionStatus !== CONNECTION_STATUS.CONNECTED}
                    data-testid="join-chat-room-button"
                  >
                    입장
                  </Button>
                </Table.Cell>
              </Table.Row>
            ))}
          </Table.Body>
        </Table.Root>
      </div>

      {totalPages > 1 && (
        <HStack
          $css={{ gap: '$100', justifyContent: 'center', alignItems: 'center' }}
          aria-label="채팅방 목록 페이지"
        >
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(0)}
            disabled={pageLoading || currentPage === 0}
            aria-label="첫 페이지"
          >
            처음
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(currentPage - 1)}
            disabled={pageLoading || currentPage === 0}
            aria-label="이전 페이지"
          >
            이전
          </Button>

          {visiblePages.map((page) => (
            <Button
              key={page}
              variant={page === currentPage ? 'solid' : 'outline'}
              size="sm"
              onClick={() => onPageChange(page)}
              disabled={pageLoading || page === currentPage}
              aria-current={page === currentPage ? 'page' : undefined}
              aria-label={`${page + 1} 페이지`}
            >
              {page + 1}
            </Button>
          ))}

          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(currentPage + 1)}
            disabled={pageLoading || currentPage >= totalPages - 1}
            aria-label="다음 페이지"
          >
            다음
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => onPageChange(totalPages - 1)}
            disabled={pageLoading || currentPage >= totalPages - 1}
            aria-label="마지막 페이지"
          >
            마지막
          </Button>
        </HStack>
      )}
    </VStack>
  );
};

export default RoomsTable;
