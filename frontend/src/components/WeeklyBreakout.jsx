import { useState, useEffect } from 'react';
import { weeklyApi } from '../api';

function WeeklyBreakout() {
  const [state, setState] = useState([]);
  const [watching, setWatching] = useState([]);
  const [alerted, setAlerted] = useState([]);
  const [activeTab, setActiveTab] = useState('all');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');

  const loadData = async () => {
    try {
      const [allState, watchingData, alertedData] = await Promise.all([
        weeklyApi.getState(),
        weeklyApi.getWatching(),
        weeklyApi.getAlerted()
      ]);
      setState(allState);
      setWatching(watchingData);
      setAlerted(alertedData);
    } catch (error) {
      setMessage('Error loading data: ' + error.message);
    }
  };

  useEffect(() => {
    loadData();
  }, []);

  const handleCapture = async () => {
    setLoading(true);
    try {
      const result = await weeklyApi.capture();
      setMessage(`Seeded ${result.symbolsLoaded} symbols`);
      await loadData();
    } catch (error) {
      setMessage('Error capturing: ' + error.message);
    }
    setLoading(false);
  };

  const handleScan = async () => {
    setLoading(true);
    try {
      await weeklyApi.scan();
      setMessage('Scan completed');
      await loadData();
    } catch (error) {
      setMessage('Error scanning: ' + error.message);
    }
    setLoading(false);
  };

  const getDisplayData = () => {
    switch (activeTab) {
      case 'watching': return watching;
      case 'alerted': return alerted;
      default: return state;
    }
  };

  const formatNumber = (num) => num ? num.toFixed(2) : 'N/A';

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>🇮🇳 NSE Weekly Breakout Scanner</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      {/* Control Panel */}
      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Control Panel</h3>
        <div style={styles.buttonGroup}>
          <button 
            onClick={handleCapture} 
            disabled={loading}
            style={styles.button}
          >
            📊 Seed (Capture)
          </button>
          <button 
            onClick={handleScan} 
            disabled={loading}
            style={styles.button}
          >
            🔍 Scan
          </button>
          <button 
            onClick={loadData} 
            disabled={loading}
            style={styles.button}
          >
            🔄 Refresh
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div style={styles.tabs}>
        {['all', 'watching', 'alerted'].map(tab => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            style={{
              ...styles.tab,
              ...(activeTab === tab ? styles.activeTab : {})
            }}
          >
            {tab.toUpperCase()} ({tab === 'all' ? state.length : 
              tab === 'watching' ? watching.length : alerted.length})
          </button>
        ))}
      </div>

      {/* Data Table */}
      <div style={styles.tableContainer}>
        <table style={styles.table}>
          <thead>
            <tr>
              <th style={styles.th}>Symbol</th>
              <th style={styles.th}>Weekly High</th>
              <th style={styles.th}>Weekly Low</th>
              <th style={styles.th}>Prev Day High</th>
              <th style={styles.th}>Prev Day Low</th>
              <th style={styles.th}>Buy Alerted</th>
              <th style={styles.th}>Sell Alerted</th>
            </tr>
          </thead>
          <tbody>
            {getDisplayData().map((item, index) => (
              <tr key={index} style={styles.tr}>
                <td style={styles.td}><strong>{item.symbol}</strong></td>
                <td style={styles.td}>₹{formatNumber(item.weeklyHigh)}</td>
                <td style={styles.td}>₹{formatNumber(item.weeklyLow)}</td>
                <td style={styles.td}>₹{formatNumber(item.prevDailyHigh)}</td>
                <td style={styles.td}>₹{formatNumber(item.prevDailyLow)}</td>
                <td style={styles.td}>
                  {item.buyAlerted ? <span style={styles.buyAlert}>✓</span> : '—'}
                </td>
                <td style={styles.td}>
                  {item.sellAlerted ? <span style={styles.sellAlert}>✓</span> : '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {getDisplayData().length === 0 && (
          <div style={styles.emptyState}>No data available</div>
        )}
      </div>
    </div>
  );
}

const styles = {
  container: {
    padding: '20px',
    background: '#f8f9fa',
    borderRadius: '8px',
    marginBottom: '20px'
  },
  title: {
    color: '#333',
    marginBottom: '20px',
    fontSize: '22px'
  },
  message: {
    background: '#e3f2fd',
    border: '1px solid #2196f3',
    borderRadius: '8px',
    padding: '12px',
    marginBottom: '20px',
    display: 'flex',
    justifyContent: 'space-between',
    alignItems: 'center',
    color: '#1976d2'
  },
  closeBtn: {
    background: 'none',
    border: 'none',
    fontSize: '20px',
    cursor: 'pointer',
    color: '#1976d2'
  },
  section: {
    marginBottom: '20px',
    padding: '15px',
    background: 'white',
    borderRadius: '8px',
    border: '1px solid #e0e0e0'
  },
  sectionTitle: {
    color: '#333',
    marginBottom: '10px',
    fontSize: '16px'
  },
  button: {
    padding: '10px 20px',
    background: '#28a745',
    color: 'white',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: '600',
    transition: 'background 0.2s'
  },
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
  },
  tabs: {
    display: 'flex',
    gap: '5px',
    marginBottom: '15px',
    flexWrap: 'wrap'
  },
  tab: {
    padding: '8px 16px',
    background: '#e0e0e0',
    border: 'none',
    borderRadius: '6px',
    cursor: 'pointer',
    fontSize: '13px',
    fontWeight: '500'
  },
  activeTab: {
    background: '#28a745',
    color: 'white'
  },
  tableContainer: {
    overflowX: 'auto'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse',
    fontSize: '13px'
  },
  th: {
    background: '#28a745',
    color: 'white',
    padding: '10px',
    textAlign: 'left',
    fontWeight: '600',
    whiteSpace: 'nowrap'
  },
  tr: {
    borderBottom: '1px solid #e0e0e0'
  },
  td: {
    padding: '10px',
    color: '#333'
  },
  buyAlert: {
    color: '#4caf50',
    fontWeight: 'bold',
    fontSize: '14px'
  },
  sellAlert: {
    color: '#f44336',
    fontWeight: 'bold',
    fontSize: '14px'
  },
  emptyState: {
    textAlign: 'center',
    padding: '30px',
    color: '#999',
    fontSize: '14px'
  }
};

export default WeeklyBreakout;
