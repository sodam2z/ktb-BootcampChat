import React, { useState, useEffect, useRef } from 'react';
import {
  PdfIcon as FileText,
  ImageIcon as Image,
  MovieIcon as Film,
  PlayIcon as Music,
  ErrorCircleIcon as AlertCircle
} from '@vapor-ui/icons';
import { VStack, HStack } from '@vapor-ui/core';
import CustomAvatar from './CustomAvatar';
import MessageContent from './MessageContent';
import MessageActions from './MessageActions';
import FileActions from './FileActions';
import ReadStatus from './ReadStatus';
import fileService from '@/services/fileService';
import { useAuth } from '@/contexts/AuthContext';

const FileMessage = ({
  msg = {},
  isMine = false,
  currentUser = null,
  onReactionAdd,
  onReactionRemove,
  room = null
}) => {
  const { user } = useAuth();
  const [error, setError] = useState(null);
  const [previewUrl, setPreviewUrl] = useState('');
  const [shouldLoadPreview, setShouldLoadPreview] = useState(false);
  const messageDomRef = useRef(null);

  useEffect(() => {
    setShouldLoadPreview(false);
    setPreviewUrl('');
    setError(null);
  }, [msg?.file?.filename]);

  useEffect(() => {
    if (!msg?.file || msg?.file?.deleted) {
      return;
    }

    if (shouldLoadPreview || typeof IntersectionObserver === 'undefined') {
      setShouldLoadPreview(true);
      return;
    }

    const target = messageDomRef.current;
    if (!target) {
      return;
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) {
          setShouldLoadPreview(true);
          observer.disconnect();
        }
      },
      {
        root: null,
        rootMargin: '300px 0px',
        threshold: 0.01,
      }
    );

    observer.observe(target);

    return () => {
      observer.disconnect();
    };
  }, [msg?.file, msg?.file?.deleted, shouldLoadPreview]);

  useEffect(() => {
    if (msg?.file?.deleted) {
      setPreviewUrl('');
      setError(null);
      return;
    }

    if (!msg?.file) {
      setPreviewUrl('');
      return;
    }

    if (!shouldLoadPreview) {
      setPreviewUrl('');
      return;
    }

    const url = fileService.getPreviewUrl(
      msg.file,
      user?.token,
      user?.sessionId,
      true
    );

    setPreviewUrl(url);
  }, [
    msg?.file,
    shouldLoadPreview,
    user?.token,
    user?.sessionId
  ]);

  if (!msg?.file) {
    console.error('File data is missing:', msg);
    return null;
  }

  const formattedTime = new Date(msg.timestamp).toLocaleString('ko-KR', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  });

  const getFileIcon = () => {
    const mimetype = msg.file?.mimetype || '';
    const iconProps = { className: 'w-5 h-5 flex-shrink-0' };

    if (mimetype.startsWith('image/')) return <Image {...iconProps} color="#00C853" />;
    if (mimetype.startsWith('video/')) return <Film {...iconProps} color="#2196F3" />;
    if (mimetype.startsWith('audio/')) return <Music {...iconProps} color="#9C27B0" />;
    return <FileText {...iconProps} color="#ffffff" />;
  };

  const getDecodedFilename = (encodedFilename) => {
    try {
      if (!encodedFilename) return 'Unknown File';

      const base64 = encodedFilename
        .replace(/-/g, '+')
        .replace(/_/g, '/');

      const pad = base64.length % 4;
      const paddedBase64 = pad ? base64 + '='.repeat(4 - pad) : base64;

      if (paddedBase64.match(/^[A-Za-z0-9+/=]+$/)) {
        const bytes = Uint8Array.from(atob(paddedBase64), (char) => char.charCodeAt(0));
        return new TextDecoder().decode(bytes);
      }

      return decodeURIComponent(encodedFilename);
    } catch (decodeError) {
      console.error('Filename decoding error:', decodeError);
      return encodedFilename;
    }
  };

  const renderAvatar = () => (
    <CustomAvatar
      user={isMine ? currentUser : msg.sender}
      size="md"
      persistent={true}
      className="shrink-0"
      showInitials={true}
    />
  );

  const requireFileAuth = () => {
    if (!msg.file?.filename) {
      throw new Error('File information is missing.');
    }

    if (!user?.token || !user?.sessionId) {
      throw new Error('Authentication information is missing.');
    }
  };

  const handleFileDownload = async (e) => {
    e.preventDefault();
    e.stopPropagation();
    setError(null);

    try {
      requireFileAuth();

      const baseUrl = fileService.getFileUrl(msg.file.filename, false);
      const authenticatedUrl = `${baseUrl}?token=${encodeURIComponent(user.token)}&sessionId=${encodeURIComponent(user.sessionId)}&download=true`;

      const iframe = document.createElement('iframe');
      iframe.style.display = 'none';
      iframe.src = authenticatedUrl;
      document.body.appendChild(iframe);

      setTimeout(() => {
        if (iframe.parentNode) {
          iframe.parentNode.removeChild(iframe);
        }
      }, 5000);
    } catch (downloadError) {
      console.error('File download error:', downloadError);
      setError(downloadError.message || 'An error occurred while downloading the file.');
    }
  };

  const handleViewInNewTab = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setError(null);

    try {
      requireFileAuth();

      const baseUrl = fileService.getFileUrl(msg.file.filename, true);
      const authenticatedUrl = `${baseUrl}?token=${encodeURIComponent(user.token)}&sessionId=${encodeURIComponent(user.sessionId)}`;

      const newWindow = window.open(authenticatedUrl, '_blank');
      if (!newWindow) {
        throw new Error('Popup was blocked. Please allow popups and try again.');
      }
      newWindow.opener = null;
    } catch (viewError) {
      console.error('File view error:', viewError);
      setError(viewError.message || 'An error occurred while opening the file.');
    }
  };

  const renderImagePreview = (originalname) => {
    try {
      if (!msg?.file?.filename) {
        return (
          <div className="flex items-center justify-center h-full bg-gray-100">
            <Image className="w-8 h-8 text-gray-400" />
          </div>
        );
      }

      if (!user?.token || !user?.sessionId) {
        throw new Error('Authentication information is missing.');
      }

      return (
        <div className="bg-transparent-pattern">
          <img
            src={previewUrl || '/images/placeholder-image.png'}
            alt={originalname}
            className="max-w-[400px] max-h-[400px] object-cover object-center rounded-md"
            onError={(e) => {
              console.error('Image load error:', { originalname });
              e.target.onerror = null;
              e.target.src = '/images/placeholder-image.png';
              setError('Failed to load image preview.');
            }}
            loading="lazy"
            data-testid="file-image-preview"
          />
        </div>
      );
    } catch (previewError) {
      console.error('Image preview error:', previewError);
      setError(previewError.message || 'Failed to load image preview.');
      return (
        <div className="flex items-center justify-center h-full bg-gray-100">
          <Image className="w-8 h-8 text-gray-400" />
        </div>
      );
    }
  };

  const renderFileMetadata = (originalname, size) => (
    <div className="flex items-center gap-2 mt-2">
      {getFileIcon()}
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium truncate text-gray-200">{originalname}</div>
        <div className="text-xs text-gray-400">{size}</div>
      </div>
    </div>
  );

  const renderFilePreview = () => {
    const mimetype = msg.file?.mimetype || '';
    const originalname = getDecodedFilename(msg.file?.originalname || 'Unknown File');
    const size = fileService.formatFileSize(msg.file?.size || 0);
    const previewWrapperClass = 'overflow-hidden';

    if (msg.file?.deleted) {
      return (
        <div className="flex items-center gap-2 text-gray-400">
          <AlertCircle className="w-5 h-5" />
          <span className="text-sm">Deleted file.</span>
        </div>
      );
    }

    if (mimetype.startsWith('image/')) {
      return (
        <div className={previewWrapperClass}>
          {renderImagePreview(originalname)}
          {renderFileMetadata(originalname, size)}
          <FileActions onViewInNewTab={handleViewInNewTab} onDownload={handleFileDownload} />
        </div>
      );
    }

    if (mimetype.startsWith('video/')) {
      return (
        <div className={previewWrapperClass}>
          <div>
            {previewUrl ? (
              <video
                className="max-w-[400px] max-h-[400px] object-cover rounded-md"
                controls
                preload="metadata"
                aria-label={`${originalname} video`}
                crossOrigin="use-credentials"
              >
                <source src={previewUrl} type={mimetype} />
                <track kind="captions" />
                Video playback is not supported.
              </video>
            ) : (
              <div className="flex items-center justify-center h-full">
                <Film className="w-8 h-8 text-gray-400" />
              </div>
            )}
          </div>
          {renderFileMetadata(originalname, size)}
          <FileActions onViewInNewTab={handleViewInNewTab} onDownload={handleFileDownload} />
        </div>
      );
    }

    if (mimetype.startsWith('audio/')) {
      return (
        <div className={previewWrapperClass}>
          {renderFileMetadata(originalname, size)}
          <div className="mt-3">
            {previewUrl && (
              <audio
                className="w-full"
                controls
                preload="metadata"
                aria-label={`${originalname} audio`}
                crossOrigin="use-credentials"
              >
                <source src={previewUrl} type={mimetype} />
                Audio playback is not supported.
              </audio>
            )}
          </div>
          <FileActions onViewInNewTab={handleViewInNewTab} onDownload={handleFileDownload} />
        </div>
      );
    }

    return (
      <div className={previewWrapperClass}>
        {renderFileMetadata(originalname, size)}
        <FileActions onViewInNewTab={handleViewInNewTab} onDownload={handleFileDownload} />
      </div>
    );
  };

  return (
    <div className="my-4" ref={messageDomRef} data-testid="file-message-container">
      <VStack
        className={`max-w-[65%] ${isMine ? 'ml-auto items-end' : 'mr-auto items-start'}`}
        align={isMine ? 'flex-end' : 'flex-start'}
        $css={{ gap: '$100' }}
      >
        <HStack className="px-1" $css={{ gap: '$100', alignItems: 'center' }}>
          {renderAvatar()}
          <span className="text-sm font-medium text-gray-300">
            {isMine ? 'Me' : msg.sender?.name || 'Unknown User'}
          </span>
        </HStack>

        <div className={`
          relative group
          rounded-2xl px-4 py-3
          border transition-all duration-200
          ${isMine
            ? 'bg-gray-800 border-blue-500 hover:border-blue-400 hover:shadow-md'
            : 'bg-transparent border-gray-400 hover:border-gray-300 hover:shadow-md'
          }
        `}>
          <div className={isMine ? 'text-blue-100' : 'text-white'}>
            {error && (
              <div>{error}</div>
            )}
            {!error && renderFilePreview()}
            {!error && msg.content && (
              <div className="mt-3 text-base leading-relaxed">
                <MessageContent content={msg.content} />
              </div>
            )}
          </div>

          <HStack
            $css={{
              gap: '$150',
              justifyContent: 'flex-end',
              alignItems: 'center',
            }}
            className={`mt-2 pt-2 border-t ${isMine ? 'border-gray-700' : 'border-gray-600'}`}
          >
            <div
              className={`text-xs ${isMine ? 'text-blue-400' : 'text-gray-300'}`}
              title={new Date(msg.timestamp).toLocaleString('ko-KR')}
            >
              {formattedTime}
            </div>
            <ReadStatus
              messageType={msg.type}
              participants={room?.participants || []}
              readers={msg.readers || []}
              messageId={msg._id}
              messageRef={messageDomRef}
              currentUserId={currentUser?._id || currentUser?.id}
            />
          </HStack>
        </div>

        <MessageActions
          messageId={msg._id}
          messageContent={msg.content}
          reactions={msg.reactions}
          currentUserId={currentUser?._id || currentUser?.id}
          onReactionAdd={onReactionAdd}
          onReactionRemove={onReactionRemove}
          isMine={isMine}
          room={room}
        />
      </VStack>
    </div>
  );
};

FileMessage.defaultProps = {
  msg: {
    file: {
      mimetype: '',
      filename: '',
      originalname: '',
      size: 0
    }
  },
  isMine: false,
  currentUser: null
};

export default React.memo(FileMessage);
