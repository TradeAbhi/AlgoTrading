import { useState } from 'react';
import { liveStrategyApi } from '../api';

function LiveStrategy() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [result, setResult] = useState(null);

  const handleScan = async () => {
    setLoading(true);
    try {
      const response = await liveStrategyApi.scan();
      setResult(response);
      setMessage(`Scan complete. Found ${response.setupsFound} setups. Telegram alert sent.`);
    } catch (error) {
      setMessage('Error running scan: ' + error.message);
    }
    setLoading(false);
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>🔴 Live Strategy Scanner</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Opening Candle Strategy</h3>
        <p style={styles.description}>
          Manually trigger the Opening Candle Strategy scan. This fetches today's 9:15 and 9:30 candles 
          for all F&O stocks, runs strategy rules, and sends Telegram alert with BUY/SELL setups.
          The scheduler runs this automatically at 9:46 AM every weekday.
        </p>
        
        <button 
          onClick={handleScan} 
          disabled={loading} 
          style={styles.primaryButton}
        >
          {loading ? 'Scanning...' : '🔍 Run Live Scan'}
        </button>
      </div>

      {result && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>Scan Result</h3>
          <div style={styles.resultGrid}>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>Status</div>
              <div style={styles.resultValue}>{result.status}</div>
            </div>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>Setups Found</div>
              <div style={styles.resultValue}>{result.setupsFound}</div>
            </div>
            <div style={styles.resultItem}>
              <div style={styles.resultLabel}>Telegram Sent</div>
              <div style={styles.resultValue}>{result.telegramSent ? '✅ Yes' : '❌ No'}</div>
            </div>
          </div>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Strategy Details</h3>
        <div style={styles.infoList}>
          <div style={styles.infoItem}>
            <strong>📅 Schedule:</strong> Runs automatically at 9:46 AM every weekday
          </div>
          <div style={styles.infoItem}>
            <strong>📊 Candles:</strong> Uses 9:15 (C1) and 9:30 (C2) 15-minute candles
          </div>
          <div style={styles.infoItem}>
            <strong>🎯 Universe:</strong> All NSE F&O stocks
          </div>
          <div style={styles.infoItem}>
            <strong>📱 Alert:</strong> Sends Telegram message with BUY/SELL setups
          </div>
          <div style={styles.infoItem}>
            <strong>⚡ Filters:</strong> ATR, volume, wick ratio, body percentage
          </div>
        </div>
      </div>
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px'
  },
  title: {
    color: '#333',
    marginBottom: '20px'
  },
  message: {
    padding: '12px',
    background: '#d4edda',
    border: '1px solid #c3e6cb',
    borderRadius: '4px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '18px',
    cursor: 'pointer'
  },
  section: {
    background: 'white',
    padding: '20px',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  sectionTitle: {
    color: '#666',
    marginBottom: '15px',
    fontSize: '18px'
  },
  description: {
    color: '#555',
    lineHeight: '1.6',
    marginBottom: '20px'
  },
  primaryButton: {
    padding: '12px 24px',
    background: '#667eea',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  resultGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
    gap: '15px'
  },
  resultItem: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '4px',
    textAlign: 'center'
  },
  resultLabel: {
    fontSize: '12px',
    color: '#666',
    marginBottom: '5px'
  },
  resultValue: {
    fontSize: '18px',
    fontWeight: 'bold',
    color: '#333'
  },
  infoList: {
    display: 'flex',
    flexDirection: 'column',
    gap: '10px'
  },
  infoItem: {
    padding: '10px',
    background: '#f8f9fa',
    borderRadius: '4px',
    fontSize: '14px',
    color: '#555'
  }
};

export default LiveStrategy;
