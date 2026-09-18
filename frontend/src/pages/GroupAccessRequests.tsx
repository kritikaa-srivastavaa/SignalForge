import { useAuth } from '../auth/AuthProvider';
import { canReviewGroup } from '../auth/permissions';
import { Forbidden } from '../components/Forbidden';
import { RequestQueue } from './AdminAccessRequests';

export function GroupAccessRequests() {
  const { user } = useAuth();
  return canReviewGroup(user) ? <RequestQueue group /> : <Forbidden />;
}
