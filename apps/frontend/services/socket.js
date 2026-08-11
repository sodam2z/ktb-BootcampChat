import { io } from 'socket.io-client';

const CLEANUP_REASONS = {
  DISCONNECT: 'disconnect',
  MANUAL: 'manual',
  RECONNECT: 'reconnect'
};

export class SocketService {
  constructor() {
    this.socket = null;
    this.reconnectAttempts = 0;
    this.maxReconnectAttempts = 5;
    this.isReconnecting = false;
    this.connectionPromise = null;
    this.connectionReject = null;
    this.connectionTimeout = null;
    this.retryDelay = 1000;
    this.connected = false;
    this.connectionKey = null;
  }

  async connect(options = {}) {
    const nextConnectionKey = this.createConnectionKey(options);

    if (this.connectionPromise) {
      return this.connectionPromise;
    }

    if (this.socket?.connected && this.connectionKey === nextConnectionKey) {
      return Promise.resolve(this.socket);
    }

    const connectionPromise = new Promise((resolve, reject) => {
      let socket = null;

      const resolveConnection = (connectedSocket) => {
        if (connectedSocket !== this.socket) {
          return;
        }

        this.clearConnectionTimeout();
        this.connectionReject = null;
        resolve(connectedSocket);
      };

      const rejectConnection = (error, failedSocket = socket) => {
        if (failedSocket && failedSocket !== this.socket) {
          return;
        }

        this.clearConnectionTimeout();
        this.connectionReject = null;
        this.cleanupSocket(failedSocket);
        reject(error);
      };

      try {
        if (this.socket) {
          this.cleanupSocket(this.socket);
        }

        const socketUrl = process.env.NEXT_PUBLIC_SOCKET_URL;

        socket = io(socketUrl, {
          ...options,
          transports: ['websocket', 'polling'],
          reconnection: true,
          reconnectionAttempts: this.maxReconnectAttempts,
          reconnectionDelay: this.retryDelay,
          reconnectionDelayMax: 5000,
          timeout: 20000,
          forceNew: options.forceNew ?? false
        });
        this.socket = socket;
        this.connectionKey = nextConnectionKey;
        this.connectionReject = (error) => rejectConnection(error, socket);
        this.connectionTimeout = setTimeout(() => {
          if (socket !== this.socket) {
            return;
          }

          if (!socket.connected) {
            rejectConnection(new Error('Connection timeout'), socket);
          }
        }, 30000);

        this.setupEventHandlers(socket, resolveConnection, rejectConnection);

      } catch (error) {
        rejectConnection(error, socket);
      }
    }).finally(() => {
      if (this.connectionPromise === connectionPromise) {
        this.connectionPromise = null;
      }
    });

    this.connectionPromise = connectionPromise;
    return this.connectionPromise;
  }

  setupEventHandlers(socket, resolve, reject) {
    socket.on('connect', () => {
      if (socket !== this.socket) {
        return;
      }

      this.connected = true;
      this.reconnectAttempts = 0;
      this.isReconnecting = false;
      resolve(socket);
    });

    socket.on('disconnect', (reason) => {
      if (socket !== this.socket) {
        return;
      }

      this.connected = false;
      this.cleanup(CLEANUP_REASONS.DISCONNECT);
    });

    socket.on('connect_error', (error) => {
      if (socket !== this.socket) {
        return;
      }

      console.log('Socket connection error:', error.message);
      if (error.message === 'Invalid session') {
        reject(error, socket);
        return;
      }
      if (error.message === 'websocket error') {
        this.reconnectAttempts++;
      }

      // 최초 연결 중일 때만 여기서 실패를 확정한다(connectionReject 가 살아 있는 동안).
      // 이미 연결됐던 소켓의 재시도 실패까지 여기서 끊으면 socket.io 의 재연결이
      // 중단돼 reconnect_failed 가 발생하지 못하고, 서버가 돌아와도 다시 붙지 않는다.
      if (this.connectionReject && this.reconnectAttempts >= this.maxReconnectAttempts) {
        reject(error, socket);
      }
    });

    // duplicate_login 이벤트 수신
    // type: 'new_login_attempt' - 새로 로그인한 디바이스
    // type: 'existing_session' - 기존 세션이 있던 디바이스 (다른 곳에서 로그인함)
    socket.on('duplicate_login', (data) => {
      if (socket !== this.socket) {
        return;
      }

      // TODO: 향후 중복 로그인 처리 필요 시 AuthContext에서 구현
    });

    socket.on('error', (error) => {
      if (socket !== this.socket) {
        return;
      }

      this.handleSocketError(error);
    });

    socket.io.on('reconnect', (attemptNumber) => {
      if (socket !== this.socket) {
        return;
      }

      this.connected = true;
      this.reconnectAttempts = 0;
      this.isReconnecting = false;
    });

    socket.io.on('reconnect_failed', () => {
      if (socket !== this.socket) {
        return;
      }

      reject(new Error('Reconnection failed'), socket);
    });
  }

  clearConnectionTimeout() {
    if (this.connectionTimeout) {
      clearTimeout(this.connectionTimeout);
      this.connectionTimeout = null;
    }
  }

  cleanupSocket(socket) {
    if (!socket) {
      return;
    }

    if (socket === this.socket) {
      this.socket = null;
      this.connected = false;
      this.connectionKey = null;
    }

    socket.disconnect();
  }

  createConnectionKey(options = {}) {
    const auth = options.auth || {};
    return `${auth.token || ''}:${auth.sessionId || ''}`;
  }

  rejectPendingConnection(error) {
    if (!this.connectionReject) {
      this.clearConnectionTimeout();
      return;
    }

    const reject = this.connectionReject;
    this.connectionReject = null;
    this.clearConnectionTimeout();
    reject(error);
  }

  cleanup(reason = CLEANUP_REASONS.MANUAL) {
    if (reason === CLEANUP_REASONS.DISCONNECT && this.isReconnecting) {
      return;
    }

    if (reason !== CLEANUP_REASONS.RECONNECT) {
      this.rejectPendingConnection(new Error('Connection disconnected'));
    }

    if (reason === CLEANUP_REASONS.MANUAL && this.socket) {
      this.cleanupSocket(this.socket);
    }

    if (reason === CLEANUP_REASONS.MANUAL) {
      this.reconnectAttempts = 0;
      this.isReconnecting = false;
      this.connectionPromise = null;
      this.connected = false;
    }
  }

  disconnect() {
    this.cleanup(CLEANUP_REASONS.MANUAL);
    if (this.socket) {
      this.cleanupSocket(this.socket);
    }
  }

  handleSocketError(error) {
    if (error.type === 'TransportError') {
      const reconnectAttempt = this.reconnect();
      if (reconnectAttempt?.catch) {
        reconnectAttempt.catch((reconnectError) => {
          console.log('Socket reconnect failed:', reconnectError.message);
        });
      }
    }
  }

  send(event, data) {
    if (!this.socket?.connected) {
      throw new Error('Socket is not connected');
    }

    this.socket.emit(event, data);
  }

  sendOn(socket, event, data) {
    if (!socket?.connected) {
      throw new Error('Socket is not connected');
    }

    socket.emit(event, data);
  }

  trySendOn(socket, event, data) {
    if (!socket?.connected) {
      return false;
    }

    try {
      socket.emit(event, data);
      return true;
    } catch (error) {
      return false;
    }
  }

  async reconnect() {
    if (this.isReconnecting) return;

    this.isReconnecting = true;
    this.rejectPendingConnection(new Error('Connection disconnected'));
    this.connectionPromise = null;

    if (this.socket) {
      this.cleanupSocket(this.socket);
    }

    try {
      await new Promise(resolve => setTimeout(resolve, this.retryDelay));
      await this.connect();
    } catch (error) {
      this.isReconnecting = false;
      throw error;
    }
  }

  isConnected() {
    return this.connected && this.socket?.connected;
  }
}

const socketService = new SocketService();

export default socketService;
