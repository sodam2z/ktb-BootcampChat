import axios, { isCancel, CancelToken } from 'axios';
import axiosInstance from './axios';
import { Toast } from '../components/Toast';
import { optimizeImageFile } from '../lib/images/optimizeImageFile';

const CHAT_IMAGE_RESIZE_ENABLED =
  process.env.NEXT_PUBLIC_CHAT_IMAGE_RESIZE_ENABLED === 'true';

const CHAT_IMAGE_RESIZE_OPTIONS = {
  maxDimension: 1600,
  quality: 0.84,
};

class FileService {
  constructor() {
    this.baseUrl = process.env.NEXT_PUBLIC_API_URL;
    this.uploadLimit = 50 * 1024 * 1024; // 50MB
    this.retryAttempts = 3;
    this.retryDelay = 1000;
    this.activeUploads = new Map();

    this.allowedTypes = {
      image: {
        extensions: ['.jpg', '.jpeg', '.png', '.gif', '.webp'],
        mimeTypes: ['image/jpeg', 'image/png', 'image/gif', 'image/webp'],
        maxSize: 10 * 1024 * 1024,
        name: '이미지'
      },
      document: {
        extensions: ['.pdf'],
        mimeTypes: ['application/pdf'],
        maxSize: 20 * 1024 * 1024,
        name: 'PDF 문서'
      }
    };
  }

  async validateFile(file) {
    if (!file) {
      const message = '파일이 선택되지 않았습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > this.uploadLimit) {
      const message = `파일 크기는 ${this.formatFileSize(this.uploadLimit)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    let isAllowedType = false;
    let maxTypeSize = 0;
    let typeConfig = null;

    for (const config of Object.values(this.allowedTypes)) {
      if (config.mimeTypes.includes(file.type)) {
        isAllowedType = true;
        maxTypeSize = config.maxSize;
        typeConfig = config;
        break;
      }
    }

    if (!isAllowedType) {
      const message = '지원하지 않는 파일 형식입니다.';
      Toast.error(message);
      return { success: false, message };
    }

    if (file.size > maxTypeSize) {
      const message = `${typeConfig.name} 파일은 ${this.formatFileSize(maxTypeSize)}를 초과할 수 없습니다.`;
      Toast.error(message);
      return { success: false, message };
    }

    const ext = this.getFileExtension(file.name);
    if (!typeConfig.extensions.includes(ext.toLowerCase())) {
      const message = '파일 확장자가 올바르지 않습니다.';
      Toast.error(message);
      return { success: false, message };
    }

    return { success: true };
  }

  async prepareUploadFile(file) {
    if (!CHAT_IMAGE_RESIZE_ENABLED || !file?.type?.startsWith('image/')) {
      return file;
    }

    return optimizeImageFile(file, CHAT_IMAGE_RESIZE_OPTIONS);
  }

async uploadFile(file, onProgress, token, sessionId) {
  const uploadFile = await this.prepareUploadFile(file);
  const validationResult = await this.validateFile(uploadFile);

  if (!validationResult.success) {
    return validationResult;
  }

  const source = CancelToken.source();
  this.activeUploads.set(file.name, source);

  const presignUrl = this.baseUrl
    ? `${this.baseUrl}/api/files/presign`
    : '/api/files/presign';

  const completeUrl = this.baseUrl
    ? `${this.baseUrl}/api/files/upload/complete`
    : '/api/files/upload/complete';

  try {
    /*
     * 1. Backend에 presigned PUT URL 요청
     *
     * 파일 본문은 보내지 않고
     * 이름 / 타입 / 크기만 전달한다.
     */
    const presignResponse = await axiosInstance.post(
      presignUrl,
      {
        originalname: uploadFile.name,
        mimetype: uploadFile.type,
        size: uploadFile.size
      },
      {
        timeout: 10000,
        cancelToken: source.token,
        withCredentials: true
      }
    );

    const presignData = presignResponse.data;

    if (
      !presignData?.success ||
      !presignData?.uploadUrl ||
      !presignData?.key
    ) {
      throw new Error(
        presignData?.message ||
        'S3 업로드 주소를 발급받지 못했습니다.'
      );
    }

    /*
     * 2. Browser → S3 직접 업로드
     *
     * axiosInstance가 아니라 일반 axios를 사용한다.
     *
     * 이유:
     * S3 요청에는 우리 Backend의
     * Authorization / session header를 붙이면 안 된다.
     */
    await axios.put(
      presignData.uploadUrl,
      uploadFile,
      {
        headers: {
          'Content-Type': uploadFile.type,
          'Cache-Control':
            'private, no-cache, no-store, must-revalidate'
        },
        timeout: 30000,
        cancelToken: source.token,

        onUploadProgress: (progressEvent) => {
          if (
            onProgress &&
            progressEvent.total
          ) {
            const percentCompleted = Math.round(
              (progressEvent.loaded * 100) /
              progressEvent.total
            );

            onProgress(percentCompleted);
          }
        }
      }
    );

    /*
     * 3. S3 업로드 완료 후 Backend에 metadata 저장 요청
     *
     * Backend는 S3 HEAD로 실제 객체를 확인하고
     * MongoDB files 컬렉션에 metadata를 저장한다.
     */
    const completeResponse = await axiosInstance.post(
      completeUrl,
      {
        key: presignData.key,
        originalname: uploadFile.name,
        mimetype: uploadFile.type,
        size: uploadFile.size
      },
      {
        timeout: 10000,
        cancelToken: source.token,
        withCredentials: true
      }
    );

    const completeData = completeResponse.data;

    if (
      !completeData?.success ||
      !completeData?.file
    ) {
      throw new Error(
        completeData?.message ||
        '파일 정보를 저장하지 못했습니다.'
      );
    }

    const fileData = completeData.file;

    return {
      success: true,
      data: {
        ...completeData,
        file: {
          ...fileData,
          url: this.getFileUrl(
            fileData.filename,
            true
          )
        }
      }
    };

  } catch (error) {
    if (isCancel(error)) {
      return {
        success: false,
        message: '업로드가 취소되었습니다.'
      };
    }

    if (error.response?.status === 401) {
      throw new Error(
        'Authentication expired. Please login again.'
      );
    }

    return this.handleUploadError(error);

  } finally {
    this.activeUploads.delete(file.name);
  }
}
  getFileUrl(filename, forPreview = false) {
    if (!filename) return '';

    const baseUrl = process.env.NEXT_PUBLIC_API_URL || '';
    const endpoint = forPreview ? 'view' : 'download';
    return `${baseUrl}/api/files/${endpoint}/${filename}`;
  }

  getPreviewUrl(file, token, sessionId, withAuth = true) {
    if (!file?.filename) return '';

    const baseUrl = `${process.env.NEXT_PUBLIC_API_URL}/api/files/view/${file.filename}`;

    if (!withAuth) return baseUrl;

    if (!token || !sessionId) return baseUrl;

    // URL 객체 생성 전 프로토콜 확인
    const url = new URL(baseUrl);
    url.searchParams.append('token', encodeURIComponent(token));
    url.searchParams.append('sessionId', encodeURIComponent(sessionId));

    return url.toString();
  }

  getFileExtension(filename) {
    if (!filename) return '';
    const parts = filename.split('.');
    return parts.length > 1 ? `.${parts.pop().toLowerCase()}` : '';
  }

  formatFileSize(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const i = Math.floor(Math.log(bytes) / Math.log(1024));
    return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`;
  }

  handleUploadError(error) {
    if (error.code === 'ECONNABORTED') {
      return {
        success: false,
        message: '파일 업로드 시간이 초과되었습니다.'
      };
    }

    const status = error.response?.status ?? error.status;
    const message = error.response?.data?.message ?? error.message;

    switch (status) {
      case 400:
        return {
          success: false,
          message: message || '잘못된 요청입니다.'
        };
      case 401:
        return {
          success: false,
          message: '인증이 필요합니다.'
        };
      case 413:
        return {
          success: false,
          message: message || '파일이 너무 큽니다.'
        };
      case 415:
        return {
          success: false,
          message: '지원하지 않는 파일 형식입니다.'
        };
      default:
        break;
    }

    console.error('Upload error:', error);

    if (axios.isAxiosError(error)) {
      switch (status) {
        case 500:
          return {
            success: false,
            message: '서버 오류가 발생했습니다.'
          };
        default:
          return {
            success: false,
            message: message || '파일 업로드에 실패했습니다.'
          };
      }
    }

    return {
      success: false,
      message: error.message || '알 수 없는 오류가 발생했습니다.',
      error
    };
  }

  cancelUpload(filename) {
    const source = this.activeUploads.get(filename);
    if (source) {
      source.cancel('Upload canceled by user');
      this.activeUploads.delete(filename);
      return {
        success: true,
        message: '업로드가 취소되었습니다.'
      };
    }
    return {
      success: false,
      message: '취소할 업로드를 찾을 수 없습니다.'
    };
  }

}

export default new FileService();
