import { useCallback, useMemo, useState } from 'react';
import { Toast } from '@/components/Toast';
import socketClient from '@/lib/socket/socketClient';

export const useReactionHandling = ({ currentUser, messages, setMessages }) => {
  const [pendingReactions] = useState(new Map());
  const reactionsByMessageId = useMemo(() => {
    if (!Array.isArray(messages)) {
      return new Map();
    }

    return new Map(messages.map((message) => [
      message._id,
      message.reactions || {},
    ]));
  }, [messages]);

  const handleReactionAdd = useCallback(async (messageId, reaction) => {
    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트
      setMessages(prevMessages =>
        prevMessages.map(msg => {
          if (msg._id === messageId) {
            const currentReactions = msg.reactions || {};
            const currentUsers = currentReactions[reaction] || [];

            // 중복 추가 방지
            if (!currentUsers.includes(currentUser.id)) {
              return {
                ...msg,
                reactions: {
                  ...currentReactions,
                  [reaction]: [...currentUsers, currentUser.id]
                }
              };
            }
          }
          return msg;
        })
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'add');

    } catch (error) {
      console.error('Add reaction error:', error);
      Toast.error('리액션 추가에 실패했습니다.');

      // 실패 시 롤백
      const previousReactions = reactionsByMessageId.get(messageId) || {};
      setMessages(prevMessages =>
        prevMessages.map(msg =>
          msg._id === messageId ?
          { ...msg, reactions: previousReactions } :
          msg
        )
      );
    }
  }, [currentUser, reactionsByMessageId, setMessages]);

  const handleReactionRemove = useCallback(async (messageId, reaction) => {
    try {
      if (!socketClient.canSend()) {
        throw new Error('Socket not connected');
      }

      // 낙관적 업데이트
      setMessages(prevMessages =>
        prevMessages.map(msg => {
          if (msg._id === messageId) {
            const currentReactions = msg.reactions || {};
            const currentUsers = currentReactions[reaction] || [];
            return {
              ...msg,
              reactions: {
                ...currentReactions,
                [reaction]: currentUsers.filter(id => id !== currentUser.id)
              }
            };
          }
          return msg;
        })
      );

      await socketClient.sendMessageReaction(messageId, reaction, 'remove');

    } catch (error) {
      console.error('Remove reaction error:', error);
      Toast.error('리액션 제거에 실패했습니다.');

      // 실패 시 롤백
      const previousReactions = reactionsByMessageId.get(messageId) || {};
      setMessages(prevMessages =>
        prevMessages.map(msg =>
          msg._id === messageId ?
          { ...msg, reactions: previousReactions } :
          msg
        )
      );
    }
  }, [currentUser, reactionsByMessageId, setMessages]);

  const handleReactionUpdate = useCallback(({ messageId, reactions }) => {
    setMessages(prevMessages =>
      prevMessages.map(msg =>
        msg._id === messageId ? { ...msg, reactions } : msg
      )
    );
  }, [setMessages]);

  return {
    handleReactionAdd,
    handleReactionRemove,
    handleReactionUpdate
  };
};

export default useReactionHandling;
