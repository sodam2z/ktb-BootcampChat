import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SocketService } from '../socket';
import { io } from 'socket.io-client';

vi.mock('socket.io-client', () => ({
  io: vi.fn(),
}));

const createSocket = ({ connected = false } = {}) => ({
  connected,
  emit: vi.fn(),
  on: vi.fn(),
  disconnect: vi.fn(),
  io: {
    on: vi.fn(),
    off: vi.fn(),
    opts: {},
  },
});

const flushPromises = async () => {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
};

const getSocketHandler = (socket, event) =>
  socket.on.mock.calls.find(([registeredEvent]) => registeredEvent === event)?.[1];

const getManagerHandler = (socket, event) =>
  socket.io.on.mock.calls.find(([registeredEvent]) => registeredEvent === event)?.[1];

describe('socketService', () => {
  let service;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.clearAllMocks();
    process.env.NEXT_PUBLIC_SOCKET_URL = 'http://localhost:5002';
    service = new SocketService();
  });

  afterEach(() => {
    service.disconnect();
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('rejects a pending connection immediately when disconnected', async () => {
    io.mockReturnValue(createSocket());

    service.connect().catch(() => {});
    const pendingConnection = service.connectionPromise;
    const settledConnection = pendingConnection.then(
      () => 'resolved',
      error => error.message
    );

    service.disconnect();
    await flushPromises();

    await expect(settledConnection).resolves.toBe('Connection disconnected');
    await flushPromises();

    expect(service.connectionPromise).toBeNull();
    expect(service.connectionReject).toBeNull();
    expect(service.connectionTimeout).toBeNull();
  });

  it('registers reconnect lifecycle handlers on the Socket.IO manager', () => {
    const socket = createSocket();
    io.mockReturnValue(socket);

    service.connect().catch(() => {});

    expect(socket.io.on).toHaveBeenCalledWith('reconnect', expect.any(Function));
    expect(socket.io.on).toHaveBeenCalledWith('reconnect_failed', expect.any(Function));
    expect(socket.on).not.toHaveBeenCalledWith('reconnect', expect.any(Function));
    expect(socket.on).not.toHaveBeenCalledWith('reconnect_failed', expect.any(Function));
  });

  it('reuses an already connected socket for the same session', async () => {
    const socket = createSocket({ connected: false });
    io.mockReturnValue(socket);

    const firstConnection = service.connect({
      auth: { token: 'token-1', sessionId: 'session-1' },
    });
    socket.connected = true;
    getSocketHandler(socket, 'connect')();
    await expect(firstConnection).resolves.toBe(socket);

    await expect(service.connect({
      auth: { token: 'token-1', sessionId: 'session-1' },
    })).resolves.toBe(socket);

    expect(io).toHaveBeenCalledTimes(1);
    expect(socket.disconnect).not.toHaveBeenCalled();
  });

  it('does not force a new Socket.IO manager by default', () => {
    const socket = createSocket();
    io.mockReturnValue(socket);

    service.connect({ auth: { token: 'token-1', sessionId: 'session-1' } }).catch(() => {});

    expect(io).toHaveBeenCalledWith('http://localhost:5002', expect.objectContaining({
      forceNew: false,
    }));
  });

  it('does not let a stale manager reconnect failure clear a newer socket', async () => {
    const failedSocket = createSocket();
    const liveSocket = createSocket({ connected: true });
    io.mockReturnValueOnce(failedSocket).mockReturnValueOnce(liveSocket);

    const failedConnection = service.connect().catch(error => error.message);
    getSocketHandler(failedSocket, 'connect_error')(new Error('Invalid session'));
    await flushPromises();

    await expect(failedConnection).resolves.toBe('Invalid session');

    const liveConnection = service.connect();
    getSocketHandler(liveSocket, 'connect')();
    await expect(liveConnection).resolves.toBe(liveSocket);

    getManagerHandler(failedSocket, 'reconnect_failed')();
    await flushPromises();

    expect(service.socket).toBe(liveSocket);
    expect(service.connected).toBe(true);
    expect(liveSocket.disconnect).not.toHaveBeenCalled();
  });

  it('disconnects and clears a failed socket when connection times out', async () => {
    const socket = createSocket();
    io.mockReturnValue(socket);

    const connection = service.connect().catch(error => error.message);

    await vi.advanceTimersByTimeAsync(30000);
    await flushPromises();

    await expect(connection).resolves.toBe('Connection timeout');
    expect(socket.disconnect).toHaveBeenCalledTimes(1);
    expect(service.socket).toBeNull();
    expect(service.connected).toBe(false);
  });

  it('starts a fresh reconnect when reconnect is requested during a pending connection', async () => {
    const pendingSocket = createSocket();
    const reconnectedSocket = createSocket({ connected: true });
    io.mockReturnValueOnce(pendingSocket).mockReturnValueOnce(reconnectedSocket);

    service.connect().catch(() => {});
    const pendingConnection = service.connectionPromise;
    const settledPendingConnection = pendingConnection.then(
      () => 'resolved',
      error => error.message
    );

    const reconnectAttempt = service.reconnect();
    const settledReconnect = reconnectAttempt.then(
      () => 'resolved',
      error => error.message
    );
    await flushPromises();

    await expect(
      Promise.race([
        settledPendingConnection,
        Promise.resolve('pending'),
      ])
    ).resolves.toBe('Connection disconnected');
    expect(service.connectionPromise).toBeNull();
    expect(service.connectionReject).toBeNull();
    expect(service.connectionTimeout).toBeNull();
    expect(pendingSocket.disconnect).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1000);
    await flushPromises();

    expect(io).toHaveBeenCalledTimes(2);
    expect(service.socket).toBe(reconnectedSocket);

    getSocketHandler(reconnectedSocket, 'connect')();
    await expect(settledReconnect).resolves.toBe('resolved');
    expect(service.isReconnecting).toBe(false);
    expect(service.connected).toBe(true);
  });

  it('leaves transport recovery to the Socket.IO manager', () => {
    service.reconnect = vi.fn();
    service.handleSocketError({ type: 'TransportError' });

    expect(service.reconnect).not.toHaveBeenCalled();
    expect(service.connected).toBe(false);
    expect(service.isReconnecting).toBe(true);
  });

  it('throws when sending through a disconnected target socket', () => {
    const socket = createSocket({ connected: false });

    expect(() => service.sendOn(socket, 'leaveRoom', 'room-1')).toThrow(
      'Socket is not connected'
    );
    expect(socket.emit).not.toHaveBeenCalled();
  });

  it.each([undefined, null])(
    'throws when sending through a missing target socket: %s',
    (socket) => {
      expect(() => service.sendOn(socket, 'leaveRoom', 'room-1')).toThrow(
        'Socket is not connected'
      );
    }
  );

  it('returns false when trying to send through a disconnected target socket', () => {
    const socket = createSocket({ connected: false });

    expect(service.trySendOn(socket, 'leaveRoom', 'room-1')).toBe(false);
    expect(socket.emit).not.toHaveBeenCalled();
  });

  it.each([undefined, null])(
    'returns false when trying to send through a missing target socket: %s',
    (socket) => {
      expect(service.trySendOn(socket, 'leaveRoom', 'room-1')).toBe(false);
    }
  );

  it('sends through a connected target socket', () => {
    const socket = createSocket({ connected: true });

    service.sendOn(socket, 'leaveRoom', 'room-1');

    expect(socket.emit).toHaveBeenCalledWith('leaveRoom', 'room-1');
    expect(service.trySendOn(socket, 'leaveRoom', 'room-1')).toBe(true);
    expect(socket.emit).toHaveBeenCalledTimes(2);
  });
});
