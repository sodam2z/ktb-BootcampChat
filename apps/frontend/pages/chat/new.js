import React, { useState, useEffect } from 'react';
import { useRouter } from 'next/router';
import { ErrorCircleIcon } from '@vapor-ui/icons';
import {
  Box,
  Button,
  Field,
  Form,
  HStack,
  Switch,
  Text,
  TextInput,
  VStack,
  Callout
} from '@vapor-ui/core';
import { useAuth } from '@/contexts/AuthContext';
import api from '@/lib/api/client';

function NewChatRoom() {
  const router = useRouter();
  const { user: currentUser } = useAuth();
  const [formData, setFormData] = useState({
    name: '',
    hasPassword: false,
    password: ''
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();

    if (!formData.name.trim()) {
      setError('채팅방 이름을 입력해주세요.');
      return;
    }

    if (formData.hasPassword && !formData.password) {
      setError('비밀번호를 입력해주세요.');
      return;
    }

    if (!currentUser?.token) {
      setError('인증 정보가 없습니다. 다시 로그인해주세요.');
      return;
    }

    try {
      setLoading(true);
      setError('');

      const response = await api.post('/api/rooms', {
          name: formData.name.trim(),
          password: formData.hasPassword ? formData.password : undefined
      });

      const { data } = response.data;
      // createRoom already persists the creator in participantIds. Calling the
      // HTTP join endpoint again adds Mongo reads and a global roomUpdated emit.
      await router.push(`/chat/${data._id}`);

    } catch (error) {
      console.error('Room creation/join error:', error);
      setError(error.message);
    } finally {
      setLoading(false);
    }
  };

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
          width: '400px',
          padding: '$400',
          borderRadius: '$300',
          border: '1px solid var(--vapor-color-border-normal)',
          backgroundColor: 'var(--vapor-color-surface-raised)',
        }}
        render={<Form onSubmit={handleSubmit} />}
      >
        <Text typography="heading4">새 채팅방</Text>

        {error && (
          <Callout.Root colorPalette="danger">
            <HStack $css={{ gap: '$200', alignItems: 'center' }}>
              <Callout.Icon>
                <ErrorCircleIcon size={16} />
              </Callout.Icon>
              <Text>{error}</Text>
            </HStack>
          </Callout.Root>
        )}

        <VStack $css={{ gap: '$300', width: '100%' }}>
          <Field.Root>
            <Box render={<Field.Label />} $css={{ flexDirection: 'column' }}>
              <Text typography="subtitle2" foreground="normal-200">
                채팅방 이름
              </Text>
              <TextInput
                id="room-name"
                required
                size="lg"
                placeholder="채팅방 이름을 입력하세요"
                value={formData.name}
                onChange={(e) => setFormData(prev => ({ ...prev, name: e.target.value }))}
                disabled={loading}
                data-testid="chat-room-name-input"
              />
            </Box>
            <Field.Error match="valueMissing">채팅방 이름을 입력해주세요.</Field.Error>
          </Field.Root>

          <Field.Root>
            <HStack
              render={<Field.Label />}
              $css={{ width: '100%', justifyContent: 'space-between' }}
            >
              비밀번호 설정
              <Switch.Root
                id="room-password-toggle"
                checked={formData.hasPassword}
                onCheckedChange={(checked) => setFormData(prev => ({
                  ...prev,
                  hasPassword: checked,
                  password: checked ? prev.password : ''
                }))}
                disabled={loading}
              />
            </HStack>
          </Field.Root>

          {formData.hasPassword && (
            <Field.Root>
              <Box render={<Field.Label />} $css={{ flexDirection: 'column' }}>
                <Text typography="subtitle2" foreground="normal-200">
                  비밀번호
                </Text>
                <TextInput
                  id="room-password"
                  type="password"
                  size="lg"
                  placeholder="비밀번호를 입력하세요"
                  value={formData.password}
                  onChange={(e) => setFormData(prev => ({ ...prev, password: e.target.value }))}
                  disabled={loading}
                />
              </Box>
            </Field.Root>
          )}

          <Button
            type="submit"
            size="lg"
            disabled={loading || !formData.name.trim() || (formData.hasPassword && !formData.password)}
            data-testid="create-chat-room-button"
          >
            {loading ? '생성 중...' : '채팅방 만들기'}
          </Button>
        </VStack>
      </VStack>
    </Box>
  );
}

export default NewChatRoom;
