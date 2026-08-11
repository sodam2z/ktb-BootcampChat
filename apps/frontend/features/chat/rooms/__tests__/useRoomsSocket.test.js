import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import socketClient from '@/lib/socket/socketClient';
import { useRoomsSocket } from '../useRoomsSocket';

vi.mock('@/lib/socket/socketClient', () => ({
  default: { connect: vi.fn() },
}));

const currentUser = { token: 'token-1', sessionId: 'session-1' };

describe('useRoomsSocket', () => {
  let handlers;
  let socket;

  beforeEach(() => {
    vi.clearAllMocks();
    handlers = {};
    socket = {
      on: vi.fn((event, handler) => {
        handlers[event] = handler;
      }),
      off: vi.fn((event) => {
        delete handlers[event];
      }),
      emit: vi.fn(),
      disconnect: vi.fn(),
    };
    socketClient.connect.mockResolvedValue(socket);
  });

  const renderRoomsSocket = (currentPage = 0) => {
    const setRooms = vi.fn();
    const setMetadata = vi.fn();
    const setConnectionStatus = vi.fn();
    const result = renderHook(
      ({ page }) => useRoomsSocket({
        currentUser,
        currentPage: page,
        setRooms,
        setMetadata,
        setConnectionStatus,
      }),
      { initialProps: { page: currentPage } },
    );
    return { ...result, setRooms, setMetadata };
  };

  it('does not emit joinRoomList because the server joins room-list on connect', async () => {
    renderRoomsSocket();
    await waitFor(() => expect(handlers.connect).toBeTypeOf('function'));

    act(() => handlers.connect());

    expect(socket.emit).not.toHaveBeenCalledWith('joinRoomList');
  });

  it('does not register roomDeleted without a server-side room delete event', async () => {
    renderRoomsSocket();
    await waitFor(() => expect(socket.on).toHaveBeenCalled());

    expect(Object.keys(handlers)).not.toContain('roomDeleted');
  });

  it('removes room-list listeners without closing the shared socket on cleanup', async () => {
    const { unmount } = renderRoomsSocket();
    await waitFor(() => expect(handlers.roomCreated).toBeTypeOf('function'));

    unmount();

    expect(socket.off).toHaveBeenCalledWith('connect', expect.any(Function));
    expect(socket.off).toHaveBeenCalledWith('disconnect', expect.any(Function));
    expect(socket.off).toHaveBeenCalledWith('error', expect.any(Function));
    expect(socket.off).toHaveBeenCalledWith('roomCreated', expect.any(Function));
    expect(socket.off).toHaveBeenCalledWith('roomUpdated', expect.any(Function));
    expect(socket.off).toHaveBeenCalledWith('roomActivity', expect.any(Function));
    expect(socket.disconnect).not.toHaveBeenCalled();
  });

  it('prepends a created room on the first page and keeps only 20 rows', async () => {
    const { setRooms, setMetadata } = renderRoomsSocket(0);
    await waitFor(() => expect(handlers.roomCreated).toBeTypeOf('function'));

    act(() => handlers.roomCreated({ _id: 'new-room' }));

    const updateRooms = setRooms.mock.calls[0][0];
    const previousRooms = Array.from({ length: 20 }, (_, index) => ({ _id: `room-${index}` }));
    expect(updateRooms(previousRooms)).toHaveLength(20);
    expect(updateRooms(previousRooms)[0]).toEqual({ _id: 'new-room' });

    const updateMetadata = setMetadata.mock.calls[0][0];
    expect(updateMetadata({ total: 20, pageSize: 20, totalPages: 1 })).toMatchObject({
      total: 21,
      totalPages: 2,
    });
  });

  it('does not shift rows when a room is created on a later page', async () => {
    const { rerender, setRooms, setMetadata } = renderRoomsSocket(0);
    await waitFor(() => expect(handlers.roomCreated).toBeTypeOf('function'));
    rerender({ page: 2 });

    act(() => handlers.roomCreated({ _id: 'new-room' }));

    expect(setRooms).not.toHaveBeenCalled();
    expect(setMetadata).toHaveBeenCalledTimes(1);
  });

  it('merges room updates only into a matching visible row', async () => {
    const { setRooms } = renderRoomsSocket(1);
    await waitFor(() => expect(handlers.roomUpdated).toBeTypeOf('function'));

    act(() => handlers.roomUpdated({ _id: 'room-2', participantCount: 4 }));

    const updateRooms = setRooms.mock.calls[0][0];
    expect(updateRooms([
      { _id: 'room-1', participantCount: 1 },
      { _id: 'room-2', participantCount: 3 },
    ])).toEqual([
      { _id: 'room-1', participantCount: 1 },
      { _id: 'room-2', participantCount: 4 },
    ]);
  });

  it('merges a roomActivity update without dropping other fields', async () => {
    const { setRooms } = renderRoomsSocket();
    await waitFor(() => expect(handlers.roomActivity).toBeTypeOf('function'));

    act(() => handlers.roomActivity({ _id: 'room-2', recentMessageCount: 9 }));

    await waitFor(() => expect(setRooms).toHaveBeenCalledTimes(1));

    const updateRooms = setRooms.mock.calls[0][0];
    expect(updateRooms([
      { _id: 'room-1', name: '방1', recentMessageCount: 1 },
      { _id: 'room-2', name: '방2', recentMessageCount: 2 },
    ])).toEqual([
      { _id: 'room-1', name: '방1', recentMessageCount: 1 },
      { _id: 'room-2', name: '방2', recentMessageCount: 9 },
    ]);
  });

  it('batches bursty roomActivity updates into one room state update', async () => {
    const { setRooms } = renderRoomsSocket();
    await waitFor(() => expect(handlers.roomActivity).toBeTypeOf('function'));

    act(() => {
      handlers.roomActivity({ _id: 'room-1', recentMessageCount: 2 });
      handlers.roomActivity({ _id: 'room-2', recentMessageCount: 3 });
      handlers.roomActivity({ _id: 'room-1', recentMessageCount: 4 });
    });

    expect(setRooms).not.toHaveBeenCalled();
    await waitFor(() => expect(setRooms).toHaveBeenCalledTimes(1));

    const updateRooms = setRooms.mock.calls[0][0];
    expect(updateRooms([
      { _id: 'room-1', recentMessageCount: 1 },
      { _id: 'room-2', recentMessageCount: 1 },
      { _id: 'room-3', recentMessageCount: 1 },
    ])).toEqual([
      { _id: 'room-1', recentMessageCount: 4 },
      { _id: 'room-2', recentMessageCount: 3 },
      { _id: 'room-3', recentMessageCount: 1 },
    ]);
  });

  it('ignores a roomActivity payload without a room id', async () => {
    const { setRooms } = renderRoomsSocket();
    await waitFor(() => expect(handlers.roomActivity).toBeTypeOf('function'));

    act(() => handlers.roomActivity(undefined));

    expect(setRooms).not.toHaveBeenCalled();
  });
});
