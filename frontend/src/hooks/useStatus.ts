import { useQuery } from '@tanstack/react-query';
import { statusApi } from '../services/api';

export const useStatus = () => {
  const { data: status, isLoading: loading, error } = useQuery({
    queryKey: ['system-status'],
    queryFn: async () => {
      const response = await statusApi.getStatus();
      return response.data;
    },
    refetchInterval: 30000, // Background polling every 30s
    retry: 2,
    staleTime: 10000,
  });

  return { status, loading, error };
};
