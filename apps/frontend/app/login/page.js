import { redirect } from 'next/navigation';

const LoginRedirectPage = async ({ searchParams }) => {
  const params = new URLSearchParams();

  for (const [key, value] of Object.entries((await searchParams) ?? {})) {
    if (Array.isArray(value)) {
      value.forEach((item) => params.append(key, item));
    } else if (value !== undefined) {
      params.append(key, value);
    }
  }

  const queryString = params.toString();
  redirect(queryString ? `/?${queryString}` : '/');
};

export default LoginRedirectPage;
