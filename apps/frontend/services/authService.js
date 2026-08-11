import axios from 'axios';
import api, { getAuthHeaders, HEALTH_TIMEOUT_MS } from '../lib/api/client';
import { loadStoredUser } from '../lib/auth/authStorage';

const API_URL = process.env.NEXT_PUBLIC_API_URL;

// 유효성 검증 함수
const validateCredentials = (credentials) => {
  if (!credentials || typeof credentials !== 'object') {
    throw new Error('인증 정보가 올바르지 않습니다.');
  }

  const { email, password } = credentials;

  if (!email?.trim()) {
    throw new Error('이메일을 입력해주세요.');
  }

  if (!password) {
    throw new Error('비밀번호를 입력해주세요.');
  }

  if (typeof email !== 'string' || typeof password !== 'string') {
    throw new Error('입력값의 형식이 올바르지 않습니다.');
  }

  return {
    email: email.trim(),
    password: password
  };
};

class AuthService {
  constructor() {
  }

  /**
   * 로그인 API 호출
   * 상태 관리는 AuthContext에서 처리
   */
  async login(credentials) {
    try {
      const response = await api.post('/api/auth/login', credentials, {
        skipAuth: true,
        handleAuthError: false,
        skipRetry: true,
      });

      if (response.data?.success && response.data?.token) {
        const userData = {
          id: response.data.user._id,
          name: response.data.user.name,
          email: response.data.user.email,
          profileImage: response.data.user.profileImage,
          token: response.data.token,
          sessionId: response.data.sessionId
        };

        return userData;
      }

      throw new Error(response.data?.message || '로그인에 실패했습니다.');

    } catch (error) {
      if (error.response?.status === 401) {
        throw new Error('이메일 주소가 없거나 비밀번호가 틀렸습니다.');
      }

      if (error.response?.status === 429) {
        throw new Error('너무 많은 로그인 시도가 있었습니다. 잠시 후 다시 시도해주세요.');
      }

      if (!error.response) {
        throw new Error('서버와 통신할 수 없습니다. 잠시 후 다시 시도해주세요.');
      }

      const errorMessage = error.response?.data?.message || '로그인 중 오류가 발생했습니다.';
      throw new Error(errorMessage);
    }
  }


  /**
   * 로그아웃 API 호출
   * 상태 관리와 리다이렉션은 AuthContext에서 처리
   */
  async logout(token, sessionId) {
    try {
      if (token) {
        await api.post('/api/auth/logout', null, {
          headers: getAuthHeaders({ token, sessionId })
        });
      }
    } catch (error) {
      // 로그아웃 API 실패해도 계속 진행 (best effort)
    }
  }

  /**
   * 회원가입 API 호출
   * 상태 관리는 AuthContext에서 처리
   */
  async register(userData) {
    try {
      const response = await api.post('/api/auth/register', userData, {
        skipAuth: true,
        skipRetry: true,
      });

      if (response.data?.success) {
        return response.data;
      }

      throw new Error(response.data?.message || '회원가입에 실패했습니다.');
    } catch (error) {
      throw this._handleError(error);
    }
  }
  
  /**
   * 프로필 업데이트 API 호출
   * 상태 업데이트는 AuthContext에서 처리
   */
  async updateProfile(data, token, sessionId) {
    try {
      if (!token) {
        throw new Error('인증 정보가 없습니다.');
      }

      const response = await api.put(
        '/api/users/profile',
        data,
        {
          headers: getAuthHeaders({ token, sessionId })
        }
      );

      if (response.data?.success) {
        return response.data.user;
      }

      throw new Error(response.data?.message || '프로필 업데이트에 실패했습니다.');

    } catch (error) {
      if (error.response?.status === 401) {
        throw new Error('인증이 만료되었습니다. 다시 로그인해주세요.');
      }

      throw this._handleError(error);
    }
  }

  /**
   * 비밀번호 변경 API 호출
   */
  async changePassword(currentPassword, newPassword, token, sessionId) {
    try {
      if (!token) {
        throw new Error('인증 정보가 없습니다.');
      }

      const response = await api.put(
        '/api/users/profile',
        {
          currentPassword,
          newPassword
        },
        {
          headers: getAuthHeaders({ token, sessionId })
        }
      );

      if (response.data?.success) {
        return true;
      }

      throw new Error(response.data?.message || '비밀번호 변경에 실패했습니다.');

    } catch (error) {
      if (error.response?.status === 401) {
        if (error.response.data?.message?.includes('비밀번호가 일치하지 않습니다')) {
          throw new Error('현재 비밀번호가 일치하지 않습니다.');
        }
        throw new Error('인증이 만료되었습니다. 다시 로그인해주세요.');
      }

      throw this._handleError(error);
    }
  }  

  /**
   * @deprecated getCurrentUser는 더 이상 사용하지 않습니다.
   * useAuth 훅을 사용하여 사용자 정보에 접근하세요.
   *
   * @example
   * // ❌ Bad
   * const user = authService.getCurrentUser();
   *
   * // ✅ Good
   * const { user } = useAuth();
   */
  getCurrentUser() {
    return loadStoredUser();
  }

  /**
   * @deprecated verifyToken은 AuthContext로 이동되었습니다.
   * useAuth 훅의 verifyToken을 사용하세요.
   */
  async verifyToken() {
    throw new Error('This method has been moved to AuthContext. Use useAuth().verifyToken() instead.');
  }

  /**
   * @deprecated refreshToken은 AuthContext로 이동되었습니다.
   * useAuth 훅의 refreshToken을 사용하세요.
   */
  async refreshToken() {
    throw new Error('This method has been moved to AuthContext. Use useAuth().refreshToken() instead.');
  }

  async checkServerConnection() {
    try {
      // 클라이언트에서만 실행되도록 확인
      if (typeof window === 'undefined') {
        return false;
      }

      // API_URL이 없으면 연결 실패로 처리
      if (!API_URL) {
        throw new Error('API URL이 설정되지 않았습니다.');
      }

      const response = await api.get('/api/health', {
        timeout: HEALTH_TIMEOUT_MS,
        validateStatus: (status) => status < 500 // 5xx 에러만 실제 에러로 처리
      });

      return response.data?.status === 'ok' || response.status === 200;
    } catch (error) {
      // 네트워크 에러나 타임아웃은 더 구체적인 메시지 제공
      if (error.code === 'ECONNABORTED' || error.message.includes('timeout')) {
        throw new Error('서버 응답 시간이 초과되었습니다.');
      }
      
      if (error.code === 'ERR_NETWORK' || error.message.includes('Network Error')) {
        throw new Error('네트워크 연결을 확인해주세요.');
      }
      
      throw this._handleError(error);
    }
  }

  _handleError(error) {
    if (error.isNetworkError) return error;
    
    if (axios.isAxiosError(error)) {
      if (!error.response) {
        return new Error('서버와 통신할 수 없습니다. 네트워크 연결을 확인해주세요.');
      }

      const { status, data } = error.response;
      const message = data?.message || error.message;

      switch (status) {
        case 400:
          return new Error(message || '입력 정보를 확인해주세요.');
        case 401:
          return new Error(message || '인증에 실패했습니다.');
        case 403:
          return new Error(message || '접근 권한이 없습니다.');
        case 404:
          return new Error(message || '요청한 리소스를 찾을 수 없습니다.');
        case 409:
          return new Error(message || '이미 가입된 이메일입니다.');
        case 429:
          return new Error(message || '너무 많은 요청이 발생했습니다. 잠시 후 다시 시도해주세요.');
        case 500:
          return new Error(message || '서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
        default:
          return new Error(message || '요청 처리 중 오류가 발생했습니다.');
      }
    }

    return error;
  }
}

const authService = new AuthService();
export default authService;
