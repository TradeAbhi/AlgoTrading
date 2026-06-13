import { useState } from 'react';
import { watchlistApi } from '../api';

function Watchlist() {
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState('');
  const [activeTab, setActiveTab] = useState('full');
  const [data, setData] = useState(null);

  const loadFullWatchlist = async () => {
    setLoading(true);
    try {
      const response = await watchlistApi.getFull();
      setData(response);
      setMessage('Full watchlist loaded');
    } catch (error) {
      setMessage('Error loading watchlist: ' + error.message);
    }
    setLoading(false);
  };

  const loadCategory = async (category) => {
    setLoading(true);
    try {
      let response;
      switch(category) {
        case 'high-oi':
          response = await watchlistApi.getHighOi();
          break;
        case 'top-gainers':
          response = await watchlistApi.getTopGainers();
          break;
        case 'top-losers':
          response = await watchlistApi.getTopLosers();
          break;
        case 'active-by-value':
          response = await watchlistApi.getActiveByValue();
          break;
        case 'volume-shockers':
          response = await watchlistApi.getVolumeShockers();
          break;
        case 'only-buyers':
          response = await watchlistApi.getOnlyBuyers();
          break;
        case 'only-sellers':
          response = await watchlistApi.getOnlySellers();
          break;
      }
      setData(response);
      setMessage(`${category} loaded`);
    } catch (error) {
      setMessage('Error loading category: ' + error.message);
    }
    setLoading(false);
  };

  const triggerAlert = async () => {
    setLoading(true);
    try {
      const response = await watchlistApi.alert();
      setMessage(response.status);
    } catch (error) {
      setMessage('Error triggering alert: ' + error.message);
    }
    setLoading(false);
  };

  const renderTable = (items) => {
    if (!items || items.length === 0) {
      return <div style={styles.empty}>No data available</div>;
    }

    return (
      <div style={styles.tableContainer}>
        <table style={styles.table}>
          <thead>
            <tr>
              <th style={styles.th}>Symbol</th>
              <th style={styles.th}>Price</th>
              <th style={styles.th}>Change %</th>
              <th style={styles.th}>Volume</th>
              <th style={styles.th}>OI</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item, index) => (
              <tr key={index}>
                <td style={styles.td}>{item.symbol}</td>
                <td style={styles.td}>{item.price?.toFixed(2)}</td>
                <td style={{
                  ...styles.td,
                  color: item.changePercent >= 0 ? '#28a745' : '#dc3545'
                }}>
                  {item.changePercent?.toFixed(2)}%
                </td>
                <td style={styles.td}>{item.volume?.toLocaleString()}</td>
                <td style={styles.td}>{item.oi?.toLocaleString()}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  };

  return (
    <div style={styles.container}>
      <h2 style={styles.title}>📋 Live Market Watchlist</h2>
      
      {message && (
        <div style={styles.message}>
          {message}
          <button onClick={() => setMessage('')} style={styles.closeBtn}>×</button>
        </div>
      )}

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Actions</h3>
        <div style={styles.buttonGroup}>
          <button 
            onClick={loadFullWatchlist} 
            disabled={loading} 
            style={styles.primaryButton}
          >
            📊 Load Full Watchlist
          </button>
          <button 
            onClick={triggerAlert} 
            disabled={loading} 
            style={styles.secondaryButton}
          >
            📱 Send Telegram Alert
          </button>
        </div>
      </div>

      <div style={styles.section}>
        <h3 style={styles.sectionTitle}>Categories</h3>
        <div style={styles.tabs}>
          {['full', 'high-oi', 'top-gainers', 'top-losers', 'active-by-value', 'volume-shockers', 'only-buyers', 'only-sellers'].map(tab => (
            <button
              key={tab}
              onClick={() => {
                setActiveTab(tab);
                if (tab === 'full') {
                  loadFullWatchlist();
                } else {
                  loadCategory(tab);
                }
              }}
              disabled={loading}
              style={{
                ...styles.tab,
                ...(activeTab === tab ? styles.activeTab : {})
              }}
            >
              {tab.replace('-', ' ').toUpperCase()}
            </button>
          ))}
        </div>
      </div>

      {data && (
        <div style={styles.section}>
          <h3 style={styles.sectionTitle}>
            {activeTab.replace('-', ' ').toUpperCase()} ({Array.isArray(data) ? data.length : '7 categories'})
          </h3>
          {activeTab === 'full' ? (
            <div style={styles.categoryGrid}>
              {Object.entries(data).map(([category, items]) => (
                <div key={category} style={styles.categoryCard}>
                  <h4 style={styles.categoryTitle}>{category.replace('_', ' ').toUpperCase()}</h4>
                  {renderTable(items)}
                </div>
              ))}
            </div>
          ) : (
            renderTable(data)
          )}
        </div>
      )}
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
  buttonGroup: {
    display: 'flex',
    gap: '10px',
    flexWrap: 'wrap'
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
  secondaryButton: {
    padding: '12px 24px',
    background: '#6c757d',
    color: 'white',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '14px',
    fontWeight: '500'
  },
  tabs: {
    display: 'flex',
    gap: '8px',
    flexWrap: 'wrap'
  },
  tab: {
    padding: '8px 16px',
    background: '#e0e0e0',
    color: '#333',
    border: 'none',
    borderRadius: '4px',
    cursor: 'pointer',
    fontSize: '12px',
    fontWeight: '500'
  },
  activeTab: {
    background: '#667eea',
    color: 'white'
  },
  categoryGrid: {
    display: 'grid',
    gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))',
    gap: '20px'
  },
  categoryCard: {
    background: '#f8f9fa',
    padding: '15px',
    borderRadius: '4px'
  },
  categoryTitle: {
    color: '#333',
    marginBottom: '10px',
    fontSize: '14px'
  },
  tableContainer: {
    overflowX: 'auto'
  },
  table: {
    width: '100%',
    borderCollapse: 'collapse'
  },
  th: {
    padding: '8px',
    textAlign: 'left',
    background: '#f8f9fa',
    borderBottom: '2px solid #dee2e6',
    fontSize: '11px',
    fontWeight: '600'
  },
  td: {
    padding: '8px',
    borderBottom: '1px solid #dee2e6',
    fontSize: '11px'
  },
  empty: {
    padding: '20px',
    textAlign: 'center',
    color: '#666'
  }
};

export default Watchlist;
