import { Typography } from '@mui/material';
import HistoryList from '../components/HistoryList';

export default function HistoryPage() {
  return (
    <>
      <Typography variant="h5" component="h1" sx={{ fontWeight: 700, mb: 3 }}>
        Build History
      </Typography>
      <HistoryList />
    </>
  );
}
