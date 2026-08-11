import { useCallback } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';
import { useChatFileUpload } from '../files/useChatFileUpload';

export const getOldestMessageTimestamp = (messages) => {
  if (!Array.isArray(messages) || messages.length === 0) {
    return undefined;
  }

  let oldestTimestamp;
  let oldestValue = Number.POSITIVE_INFINITY;

  for (const message of messages) {
    const timestamp = message?.timestamp;
    if (!timestamp) continue;

    const value = Date.parse(timestamp);
    if (Number.isNaN(value)) continue;

    if (value < oldestValue) {
      oldestValue = value;
      oldestTimestamp = timestamp;
    }
  }

  return oldestTimestamp;
};

export const useMessageHandling = (
  currentUser,
  roomId,
  handleSessionError,
  messages = [],
  loadingMessages = false,
  setLoadingMessages,
  socketRef,
) => {
 const {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   setUploading,
   setUploadProgress,
   setUploadError,
   resetFileUpload,
   uploadChatFile
 } = useChatFileUpload();

  const getRoomSocket = useCallback(() => socketRef?.current ?? null, [socketRef]);

  const canSendOnRoomSocket = useCallback(() => {
    if (socketRef) {
      return Boolean(getRoomSocket()?.connected);
    }

    return socketClient.canSend();
  }, [getRoomSocket, socketRef]);

  const handleLoadMore = useCallback(() => {
    if (!canSendOnRoomSocket()) {
      return;
    }

    if (loadingMessages) {
      return;
    }

    // 가장 오래된 메시지의 타임스탬프 찾기
    const beforeTimestamp = getOldestMessageTimestamp(messages);

    if (!beforeTimestamp) {
      return;
    }

    setLoadingMessages(true);

    // Socket.IO 이벤트만 발행 - 응답은 useChatRoom의 previousMessagesLoaded 이벤트 핸들러에서 처리
    socketClient.fetchPreviousMessages({
      roomId: roomId,
      before: beforeTimestamp,
      limit: 30
    }, getRoomSocket());
  }, [roomId, loadingMessages, messages, setLoadingMessages, canSendOnRoomSocket, getRoomSocket]);

 const handleMessageSubmit = useCallback(async (messageData) => {
   const roomSocket = getRoomSocket();
   if (!canSendOnRoomSocket() || !currentUser) {
     Toast.error('채팅 서버와 연결이 끊어졌습니다.');
     return;
   }

   if (!roomId) {
     Toast.error('채팅방 정보를 찾을 수 없습니다.');
     return;
   }

   try {
      if (messageData.type === 'file') {
        const uploadResponse = await uploadChatFile(
          messageData.fileData.file,
          currentUser
        );

       await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'file',
         content: messageData.content || '',
         fileData: {
           _id: uploadResponse.data.file._id,
           filename: uploadResponse.data.file.filename,
           originalname: uploadResponse.data.file.originalname,
           mimetype: uploadResponse.data.file.mimetype,
           size: uploadResponse.data.file.size
         }
       }, roomSocket);

       resetFileUpload();

     } else if (messageData.content?.trim()) {
       await socketClient.sendChatMessageAndWait({
         room: roomId,
         type: 'text',
         content: messageData.content.trim()
       }, roomSocket);
     }

   } catch (error) {
     if (error.message?.includes('세션') ||
         error.message?.includes('인증') ||
         error.message?.includes('토큰')) {
       await handleSessionError();
       return;
     }

     // 서버가 거부한 메시지는 onError 핸들러가 이미 토스트로 알렸다.
     // 여기서 또 띄우면 같은 사유의 토스트가 두 개 뜬다.
     if (error?.code !== 'MESSAGE_REJECTED') {
       Toast.error(error.message || '메시지 전송 중 오류가 발생했습니다.');
     }
     if (messageData.type === 'file') {
       setUploadError(error.message);
       setUploading(false);
     }
   }
 }, [currentUser, roomId, handleSessionError, uploadChatFile, resetFileUpload, setUploadError, setUploading, canSendOnRoomSocket, getRoomSocket]);

 const removeFilePreview = useCallback(() => {
   resetFileUpload();
 }, [resetFileUpload]);

 return {
   filePreview,
   uploading,
   uploadProgress,
   uploadError,
   setFilePreview,
   handleMessageSubmit,
   handleLoadMore,
   removeFilePreview,
 };
};

export default useMessageHandling;
